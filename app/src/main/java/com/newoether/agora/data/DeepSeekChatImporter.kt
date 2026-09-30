package com.newoether.agora.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream

/**
 * Imports chat history from a DeepSeek export ("Settings → Export chat history").
 *
 * DeepSeek has changed the export schema several times, so the parser is
 * deliberately tolerant instead of tied to one snapshot:
 *
 *  - **Container**: a top-level array, or an object whose `data` /
 *    `conversations` / `chats` / `list` / `biz_data` (…) field holds the
 *    conversations, with one extra nesting level accepted in between.
 *  - **Conversation**: `title` / `name` / `topic` for display, `id` falls back
 *    to a title hash, timestamps come from any known date field.
 *  - **Messages**: taken from `messages` / `list` / `chat_messages` / … or, when
 *    nothing matches, from the first array of message-like objects found.
 *  - **Message**: role from `role` / `sender` / `from` / `author` (string or
 *    `{"role": …}` object), content from `content` / `message` / `text` / … as
 *    a plain string, a number, an OpenAI-style parts array, or an object with a
 *    `text` field. DeepThink reasoning is accepted under every known spelling.
 *
 * Attachments are metadata-only in the export and cannot be recovered, so they
 * are reported through the preview but never imported.
 */
class DeepSeekChatImporter {

    @Serializable
    data class DeepSeekConversation(
        val id: String = "",
        val title: String = "",
        /** Conversation time, epoch milliseconds (0 when unknown). */
        val date: Double = 0.0,
        val list: List<DeepSeekMessage> = emptyList(),
        val messages: List<DeepSeekMessage> = emptyList(),
    )

    @Serializable
    data class DeepSeekMessage(
        val role: String = "",
        val content: String = "",
        /** Epoch milliseconds of this message (0 when unknown). */
        val time: Double = 0.0,
        val deepSeekThink: String? = null,
        val thinking: String? = null,
        val reasoningContent: String? = null,
        @SerialName("reasoning_content") val reasoningContentSnake: String? = null,
        val attachments: List<DeepSeekAttachment> = emptyList(),
    )

    @Serializable
    data class DeepSeekAttachment(
        val name: String? = null,
        val url: String? = null,
    )

    data class ConversationSummary(
        val uuid: String,
        val title: String,
        val messageCount: Int
    )

    data class ImportPreview(
        val conversations: List<ConversationSummary> = emptyList(),
        val conversationCount: Int,
        val totalMessageCount: Int,
        val userMessageCount: Int,
        val assistantMessageCount: Int,
        val hasAttachments: Boolean
    )

    data class ImportResult(
        val conversationsImported: Int = 0,
        val messagesImported: Int = 0,
        val errors: List<String> = emptyList()
    )

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses a DeepSeek export stream. Rejection is by exception: callers surface
     * the localized message. Accepts raw JSON, nested containers and ZIP exports.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun extractAndParse(openStream: () -> InputStream): Result<List<DeepSeekConversation>> {
        return try {
            BufferedInputStream(openStream()).use { input ->
                val root = readRootElement(input)
                    ?: return Result.failure(Exception("File is empty"))
                val conversations = parseTopLevel(root)
                if (conversations.isNotEmpty()) Result.success(conversations)
                else Result.failure(Exception("No conversations found in DeepSeek export"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun preview(conversations: List<DeepSeekConversation>): ImportPreview {
        val summaries = conversations
            .sortedByDescending { conversationId(it).date }
            .map { conversation ->
                val messages = conversation.list.ifEmpty { conversation.messages }
                ConversationSummary(
                    uuid = conversationId(conversation).id,
                    title = conversation.title.ifBlank { "Untitled" },
                    messageCount = messages.count(::isRetainable)
                )
            }
        val allMessages = conversations.flatMap { it.list.ifEmpty { it.messages } }
        val retainable = allMessages.filter(::isRetainable)
        return ImportPreview(
            conversations = summaries,
            conversationCount = conversations.size,
            totalMessageCount = retainable.size,
            userMessageCount = retainable.count { it.role.lowercase() == "user" },
            assistantMessageCount = retainable.count { it.role.lowercase() != "user" },
            hasAttachments = allMessages.any { it.attachments.isNotEmpty() },
        )
    }

    fun toImportFormat(
        conversations: List<DeepSeekConversation>,
        selectedIds: Set<String>? = null,
    ): ClaudeChatImporter.ImportConversations {
        val chatEntities = mutableListOf<ClaudeChatImporter.ImportChatEntity>()
        val messageEntities = mutableListOf<ClaudeChatImporter.ImportMessageEntity>()

        val filtered = if (selectedIds != null) {
            conversations.filter { conversationId(it).id in selectedIds }
        } else {
            conversations
        }

        for (conversation in filtered) {
            val identity = conversationId(conversation)
            val messages = conversation.list.ifEmpty { conversation.messages }
            val retained = messages.filter(::isRetainable)
            if (retained.isEmpty()) continue

            var previousMessageId: String? = null
            var importedCount = 0
            for ((index, message) in retained.withIndex()) {
                val messageId = "ds_${identity.id}_$index"
                val isUser = message.role.lowercase() == "user"
                messageEntities.add(
                    ClaudeChatImporter.ImportMessageEntity(
                        id = messageId,
                        conversationId = identity.id,
                        parentId = previousMessageId,
                        text = message.content,
                        images = emptyList(),
                        thoughts = message.deepSeekThink
                            ?: message.thinking
                            ?: message.reasoningContent
                            ?: message.reasoningContentSnake,
                        thoughtTitle = null,
                        tokenCount = 0,
                        status = "SUCCESS",
                        participant = if (isUser) "USER" else "MODEL",
                        timestamp = toEpochMillis(message.time, identity.date),
                        thoughtTimeMs = null,
                        modelName = null,
                        toolCallJson = null,
                        attachmentMeta = null,
                    )
                )
                previousMessageId = messageId
                importedCount++
            }

            if (importedCount > 0) {
                chatEntities.add(
                    ClaudeChatImporter.ImportChatEntity(
                        id = identity.id,
                        title = conversation.title.ifBlank { "Untitled" },
                        lastUpdated = toEpochMillis(
                            conversation.date,
                            retained.lastOrNull()?.time,
                        ),
                        selectedBranchesJson = null,
                        systemPromptId = null,
                        modelId = null,
                    )
                )
            }
        }

        return ClaudeChatImporter.ImportConversations(chatEntities, messageEntities)
    }

    /** Messages worth keeping: something visible in content or reasoning. */
    private fun isRetainable(message: DeepSeekMessage): Boolean =
        message.content.isNotBlank() ||
            !message.deepSeekThink.isNullOrBlank() ||
            !message.thinking.isNullOrBlank() ||
            !message.reasoningContent.isNullOrBlank() ||
            !message.reasoningContentSnake.isNullOrBlank()

    private data class ConversationIdentity(val id: String, val date: Double)

    private fun conversationId(conversation: DeepSeekConversation): ConversationIdentity {
        val id = conversation.id.ifBlank {
            // Fallback identity: title-based hash keeps re-imports deduplicable.
            UUID.nameUUIDFromBytes(
                ("deepseek:" + conversation.title + ":" + conversation.date).toByteArray()
            ).toString()
        }
        return ConversationIdentity(id, conversation.date)
    }

    /** Epoch timestamps are normalized to milliseconds, tolerating seconds, ms and ISO strings. */
    private fun toEpochMillis(primary: Double, fallback: Double?): Long {
        val candidate = if (primary > 0) primary else (fallback ?: 0.0)
        if (candidate <= 0) return System.currentTimeMillis()
        return if (candidate > 1_000_000_000_000.0) candidate.toLong()
        else (candidate * 1000.0).toLong()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tolerant tree parsing
    // ─────────────────────────────────────────────────────────────────────

    /** Reads the JSON document, probing ZIP exports for a usable entry. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun readRootElement(input: BufferedInputStream): JsonElement? {
        if (isZip(input)) {
            ZipInputStream(input).use { zip ->
                var fallback: JsonElement? = null
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                        val element = runCatching {
                            jsonParser.decodeFromStream<JsonElement>(NonClosingInputStream(zip))
                        }.getOrNull()
                        if (element != null) {
                            // Prefer entries that look like chat data over manifest-ish files.
                            val lower = entry.name.lowercase()
                            if (lower.contains("conv") || lower.contains("chat") ||
                                lower.contains("message")
                            ) {
                                return element
                            }
                            if (fallback == null) fallback = element
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                return fallback
            }
        }
        return jsonParser.decodeFromStream<JsonElement>(input)
    }

    private fun parseTopLevel(root: JsonElement): List<DeepSeekConversation> {
        if (root is JsonArray) return root.mapNotNull(::parseConversation)
        if (root is JsonObject) {
            for (key in CONTAINER_KEYS) {
                val found = root[key]?.let(::parseContainer).orEmpty()
                if (found.isNotEmpty()) return found
            }
            return parseConversation(root)?.let { listOf(it) }.orEmpty()
        }
        return emptyList()
    }

    /** Accepts `[… ]`, `{"key": […]}` and one further nesting level of either. */
    private fun parseContainer(value: JsonElement): List<DeepSeekConversation> {
        if (value is JsonArray) return value.mapNotNull(::parseConversation)
        if (value is JsonObject) {
            for (key in CONTAINER_KEYS) {
                val found = value[key]?.let(::parseContainer).orEmpty()
                if (found.isNotEmpty()) return found
            }
            // Also tolerate maps of id → conversation.
            val mapped = value.values.mapNotNull(::parseConversation)
            if (mapped.isNotEmpty()) return mapped
            return parseConversation(value)?.let { listOf(it) }.orEmpty()
        }
        return emptyList()
    }

    private fun parseConversation(obj: JsonObject): DeepSeekConversation? {
        val title = firstString(obj, *TITLE_KEYS).orEmpty()
        val messages = findMessageArray(obj)
            ?.mapNotNull(::parseMessage)
            .orEmpty()
        if (title.isBlank() && messages.isEmpty()) return null
        return DeepSeekConversation(
            id = firstString(obj, *ID_KEYS).orEmpty(),
            title = title,
            date = firstTimestamp(obj, *CONVERSATION_DATE_KEYS),
            list = messages,
            messages = messages,
        )
    }

    private fun findMessageArray(obj: JsonObject): List<JsonObject>? {
        for (key in MESSAGE_ARRAY_KEYS) {
            val candidates = (obj[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            if (candidates.isNotEmpty() && candidates.any(::looksLikeMessage)) return candidates
        }
        // Heuristic fallback for renamed fields: first array of message-like objects.
        for (value in obj.values) {
            val candidates = (value as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            if (candidates.isNotEmpty() && candidates.all(::looksLikeMessage)) return candidates
        }
        return null
    }

    private fun looksLikeMessage(obj: JsonObject): Boolean =
        obj.keys.any { it in ROLE_KEYS } || obj.keys.any { it in CONTENT_KEYS } ||
            obj.keys.any { it in REASONING_KEYS }

    private fun parseMessage(obj: JsonObject): DeepSeekMessage? {
        val content = firstText(obj, *CONTENT_KEYS).orEmpty()
        val reasoning = firstText(obj, *REASONING_KEYS)
        if (content.isBlank() && reasoning.isNullOrBlank()) return null
        return DeepSeekMessage(
            role = parseRole(obj),
            content = content,
            time = firstTimestamp(obj, *MESSAGE_TIME_KEYS),
            deepSeekThink = reasoning?.takeIf { it.isNotBlank() },
            attachments = parseAttachments(obj),
        )
    }

    /** Role lookup accepting nested `{"author": {"role": "…"}}` shapes. */
    private fun parseRole(obj: JsonObject): String {
        for (key in ROLE_KEYS) {
            when (val value = obj[key]) {
                is JsonPrimitive -> if (value !is JsonNull) {
                    return normalizeRole(value.content.trim())
                }
                is JsonObject -> for (inner in ROLE_KEYS) {
                    val nested = value[inner] as? JsonPrimitive ?: continue
                    if (nested !is JsonNull) return normalizeRole(nested.content.trim())
                }
                else -> {}
            }
        }
        return ""
    }

    private fun normalizeRole(raw: String): String = when (raw.lowercase()) {
        "human", "me" -> "user"
        "bot", "ai", "model" -> "assistant"
        else -> raw.lowercase()
    }

    /** String content, OpenAI-style parts arrays and wrapped objects all yield text. */
    private fun firstText(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val text = extractText(obj[key]) ?: continue
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun extractText(value: JsonElement?): String? {
        if (value == null || value is JsonNull) return null
        if (value is JsonArray) {
            return value.joinToString("\n") { extractText(it).orEmpty() }
                .takeIf { it.isNotBlank() }
        }
        if (value is JsonObject) {
            for (key in CONTENT_KEYS) {
                val nested = value[key] as? JsonPrimitive ?: continue
                if (nested !is JsonNull && nested.content.isNotEmpty() &&
                    nested.content != "null"
                ) {
                    return nested.content
                }
            }
            return null
        }
        // JsonPrimitive: numbers, booleans and strings all surface their literal.
        return value.content.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val primitive = obj[key] as? JsonPrimitive ?: continue
            if (primitive is JsonNull) continue
            primitive.content.trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun firstTimestamp(obj: JsonObject, vararg keys: String): Double {
        for (key in keys) {
            val value = obj[key] ?: continue
            val millis = parseTimestamp(value) ?: continue
            if (millis > 0) return millis
        }
        return 0.0
    }

    private fun parseTimestamp(value: JsonElement): Double? {
        if (value !is JsonPrimitive || value is JsonNull) return null
        if (value.isString) return parseTimestampString(value.content.trim())
        return value.content.toDoubleOrNull()?.let(::normalizeEpochMillis)
    }

    private fun parseTimestampString(text: String): Double? {
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return normalizeEpochMillis(it.toDouble()) }
        runCatching { Instant.parse(text).toEpochMilli().toDouble() }
            .getOrNull()?.let { return it }
        // "2026-01-02 15:04:05"-style stamps, interpreted as UTC.
        runCatching {
            LocalDateTime.parse(text.replace(' ', 'T'))
                .toInstant(ZoneOffset.UTC).toEpochMilli().toDouble()
        }.getOrNull()?.let { return it }
        return null
    }

    private fun normalizeEpochMillis(candidate: Double): Double = when {
        candidate <= 0.0 -> 0.0
        candidate > 1_000_000_000_000.0 -> candidate
        else -> candidate * 1000.0
    }

    private fun parseAttachments(obj: JsonObject): List<DeepSeekAttachment> {
        for (key in ATTACHMENT_KEYS) {
            val array = obj[key] as? JsonArray ?: continue
            val attachments = array.filterIsInstance<JsonObject>().mapNotNull { item ->
                val name = firstString(item, "name", "file_name", "filename", "title")
                val url = firstString(item, "url", "link")
                if (name == null && url == null) null else DeepSeekAttachment(name, url)
            }
            if (attachments.isNotEmpty()) return attachments
        }
        return emptyList()
    }

    private companion object {
        val CONTAINER_KEYS = arrayOf(
            "data", "conversations", "chats", "chat", "history", "chat_history",
            "biz_data", "items", "list",
        )
        val MESSAGE_ARRAY_KEYS = arrayOf(
            "messages", "list", "chat_messages", "message_list", "messageList",
            "chatMessages", "message_chain", "messageChain", "msgs", "history", "items",
        )
        val TITLE_KEYS = arrayOf(
            "title", "name", "chat_name", "topic", "conversation_name", "summary",
        )
        val ID_KEYS = arrayOf(
            "id", "conversation_id", "conversationId", "uuid", "chat_id", "chatId",
            "session_id",
        )
        val CONVERSATION_DATE_KEYS = arrayOf(
            "date", "update_time", "updated_at", "updatedAt", "create_time", "created_at",
            "createTime", "last_updated", "timestamp",
        )
        val ROLE_KEYS = arrayOf("role", "sender", "from", "author", "participant")
        val CONTENT_KEYS = arrayOf(
            "content", "message", "text", "markdown", "body", "msg", "output",
        )
        val REASONING_KEYS = arrayOf(
            "deepSeekThink", "deep_seek_think", "thinking", "reasoning",
            "reasoning_content", "reasoningContent", "reasoning_contents", "thought",
            "thoughts", "think",
        )
        val MESSAGE_TIME_KEYS = arrayOf(
            "time", "create_time", "created_at", "createdAt", "timestamp", "date",
            "update_time",
        )
        val ATTACHMENT_KEYS = arrayOf("attachments", "files", "file_list", "files_list")
    }
}
