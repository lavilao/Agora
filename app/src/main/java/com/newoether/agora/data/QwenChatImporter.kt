package com.newoether.agora.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream

/**
 * Imports chat history from a Qwen export ("Export chat → JSON" on chat.qwen.ai).
 *
 * The official export is a JSON document whose sessions carry a `chat.messages`
 * array; each assistant message may hold a `content_list` of phased fragments
 * where `phase` distinguishes `think` and `thinking_summary` (reasoning; the
 * summary's payload lives under `extra.summary_title` / `extra.summary_thought`)
 * from `answer` (the visible reply) and tool phases such as `web_search` or
 * `image_gen_tool`, which only carry process noise and are dropped. The
 * everything-at-once export arrives wrapped as `{"success": …, "request_id": …,
 * "data": [...]}` while the per-conversation export is a bare session array.
 * Community backups with a plain role/content shape are accepted through the
 * same tolerant fallbacks used by the other third-party importers:
 *
 *  - **Container**: a top-level array, or an object whose `data` /
 *    `conversations` / `chats` / `history` / `list` (…) field holds the
 *    sessions, with one extra nesting level accepted in between.
 *  - **Conversation**: `title` / `name` / `topic` for display, `id` falls back
 *    to a title hash, timestamps come from any known date field.
 *  - **Messages**: from the official `chat.messages` first, else from
 *    `messages` / `turns` / `list` / `chat_messages` / … or, when nothing
 *    matches, from the first array of message-like objects found.
 *  - **Message**: role from `role` / `sender` / `from` / `author` (string or
 *    `{"role": …}` object), content from `content_list` phases or `content` /
 *    `message` / `text` / … as a plain string, a number, an OpenAI-style parts
 *    array, or an object with a `text` field. Reasoning is accepted under every
 *    known spelling (`reasoning_content`, `thinking`, …).
 *
 * Attachments are metadata-only in the export and cannot be recovered, so they
 * are reported through the preview but never imported.
 */
class QwenChatImporter {

    @Serializable
    data class QwenConversation(
        val id: String = "",
        val title: String = "",
        /** Conversation time, epoch milliseconds (0 when unknown). */
        val date: Double = 0.0,
        val list: List<QwenMessage> = emptyList(),
        val messages: List<QwenMessage> = emptyList(),
    )

    @Serializable
    data class QwenMessage(
        val role: String = "",
        val content: String = "",
        /** Epoch milliseconds of this message (0 when unknown). */
        val time: Double = 0.0,
        /** Reasoning (`think` phases, thinking summaries, `reasoning_content`). */
        val reasoning: String? = null,
        /** Model name reported by the export for this message, when present. */
        val model: String? = null,
        val attachments: List<QwenAttachment> = emptyList(),
    )

    @Serializable
    data class QwenAttachment(
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
     * Parses a Qwen export stream. Rejection is by exception: callers surface
     * the localized message. Accepts raw JSON, nested containers and ZIP exports.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun extractAndParse(openStream: () -> InputStream): Result<List<QwenConversation>> {
        return try {
            BufferedInputStream(openStream()).use { input ->
                val root = readRootElement(input)
                    ?: return Result.failure(Exception("File is empty"))
                val conversations = parseTopLevel(root)
                if (conversations.isNotEmpty()) Result.success(conversations)
                else Result.failure(Exception("No conversations found in Qwen export"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun preview(conversations: List<QwenConversation>): ImportPreview {
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
        conversations: List<QwenConversation>,
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
                val messageId = "qw_${identity.id}_$index"
                val isUser = message.role.lowercase() == "user"
                messageEntities.add(
                    ClaudeChatImporter.ImportMessageEntity(
                        id = messageId,
                        conversationId = identity.id,
                        parentId = previousMessageId,
                        text = message.content,
                        images = emptyList(),
                        thoughts = message.reasoning,
                        thoughtTitle = null,
                        tokenCount = 0,
                        status = "SUCCESS",
                        participant = if (isUser) "USER" else "MODEL",
                        timestamp = toEpochMillis(message.time, identity.date),
                        thoughtTimeMs = null,
                        modelName = message.model?.takeIf { it.isNotBlank() }?.let { "Qwen:$it" },
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
    private fun isRetainable(message: QwenMessage): Boolean =
        message.content.isNotBlank() || !message.reasoning.isNullOrBlank()

    private data class ConversationIdentity(val id: String, val date: Double)

    private fun conversationId(conversation: QwenConversation): ConversationIdentity {
        val id = conversation.id.ifBlank {
            // Fallback identity: title-based hash keeps re-imports deduplicable.
            UUID.nameUUIDFromBytes(
                ("qwen:" + conversation.title + ":" + conversation.date).toByteArray()
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
                                lower.contains("message") || lower.contains("qwen")
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

    private fun parseTopLevel(root: JsonElement): List<QwenConversation> {
        if (root is JsonArray) {
            return root.filterIsInstance<JsonObject>().mapNotNull(::parseConversation)
        }
        if (root is JsonObject) {
            // Bare per-conversation export: `{"id": …, "chat": {"messages": […]}}`.
            if (looksLikeSession(root)) {
                return parseConversation(root)?.let { listOf(it) }.orEmpty()
            }
            for (key in CONTAINER_KEYS) {
                val found = root[key]?.let(::parseContainer).orEmpty()
                if (found.isNotEmpty()) return found
            }
            return parseConversation(root)?.let { listOf(it) }.orEmpty()
        }
        return emptyList()
    }

    /**
     * Accepts `[… ]`, `{"key": […]}` and one further nesting level of either.
     * A single session object keeps its own title and `chat.messages`.
     */
    private fun parseContainer(value: JsonElement): List<QwenConversation> {
        if (value is JsonArray) {
            return value.filterIsInstance<JsonObject>().mapNotNull(::parseConversation)
        }
        if (value is JsonObject) {
            if (looksLikeSession(value)) {
                parseConversation(value)?.let { return listOf(it) }
            }
            for (key in CONTAINER_KEYS) {
                val found = value[key]?.let(::parseContainer).orEmpty()
                if (found.isNotEmpty()) return found
            }
            // Also tolerate maps of id → conversation.
            val mapped = value.values
                .filterIsInstance<JsonObject>()
                .mapNotNull(::parseConversation)
            if (mapped.isNotEmpty()) return mapped
            return parseConversation(value)?.let { listOf(it) }.orEmpty()
        }
        return emptyList()
    }

    /** True when the object itself carries messages rather than wrapping others. */
    private fun looksLikeSession(obj: JsonObject): Boolean =
        obj["chat"] is JsonObject || findMessageArray(obj) != null

    private fun parseConversation(obj: JsonObject): QwenConversation? {
        val title = firstString(obj, *TITLE_KEYS).orEmpty()
        // The official export keeps messages under `chat.messages`; only fall
        // back to the tolerant array heuristics when no chat wrapper exists.
        val chat = obj["chat"] as? JsonObject
        val messages = if (chat != null) {
            (chat["messages"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                .orEmpty()
                .mapNotNull(::parseMessage)
        } else {
            findMessageArray(obj)
                ?.mapNotNull(::parseMessage)
                .orEmpty()
        }
        if (title.isBlank() && messages.isEmpty()) return null
        return QwenConversation(
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

    /**
     * Parses one message, preferring the official `content_list` fragments:
     * `think` and `thinking_summary` phases build the reasoning block,
     * `answer` phases build the visible text, and every other phase
     * (`web_search`, `image_gen_tool`, …) is tool noise that stays out.
     */
    private fun parseMessage(obj: JsonObject): QwenMessage? {
        val contentList = (obj["content_list"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            .orEmpty()

        val textParts = mutableListOf<String>()
        val thoughtParts = mutableListOf<String>()
        var content = ""
        var reasoning = firstText(obj, *REASONING_KEYS)?.takeIf { it.isNotBlank() }

        if (contentList.isNotEmpty()) {
            for (fragment in contentList) {
                val phase = ((fragment["phase"] as? JsonPrimitive)?.content ?: "")
                    .trim().lowercase()
                when (phase) {
                    "think" -> appendUnique(thoughtParts, extractText(fragment["content"]))
                    "thinking_summary" ->
                        appendUnique(thoughtParts, renderThinkingSummary(fragment))
                    "answer" -> appendUnique(textParts, extractText(fragment["content"]))
                    else -> {} // web_search / image_gen_tool / null: process noise.
                }
            }
            content = textParts.joinToString("\n\n")
            val joined = thoughtParts.joinToString("\n\n").takeIf { it.isNotBlank() }
            if (joined != null) {
                reasoning = if (reasoning == null) joined
                else if (!reasoning.contains(joined)) "$reasoning\n\n$joined" else reasoning
            }
        } else {
            content = firstText(obj, *CONTENT_KEYS).orEmpty()
        }

        if (content.isBlank() && reasoning.isNullOrBlank()) return null
        return QwenMessage(
            role = parseRole(obj),
            content = content,
            time = firstTimestamp(obj, *MESSAGE_TIME_KEYS),
            reasoning = reasoning?.takeIf { it.isNotBlank() },
            model = parseModel(obj),
            attachments = parseAttachments(obj),
        )
    }

    /**
     * `thinking_summary` fragments carry an empty `content`; the payload is
     * `extra.summary_title.content[]` plus `extra.summary_thought.content[]`,
     * rendered as a bold title over one bullet per thought (mirroring the
     * reference converter's output shape).
     */
    private fun renderThinkingSummary(fragment: JsonObject): String? {
        val extra = fragment["extra"] as? JsonObject ?: return null
        val parts = mutableListOf<String>()

        val titles = stringArrayAt(extra, "summary_title", "content")
        if (titles.isNotEmpty()) parts.add("**${titles.joinToString(" ")}**")
        for (thought in stringArrayAt(extra, "summary_thought", "content")) {
            parts.add("- $thought")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Reads `extra.<group>.content` as a trimmed, non-blank string list. */
    private fun stringArrayAt(extra: JsonObject, group: String, field: String): List<String> {
        val array = ((extra[group] as? JsonObject)?.get(field) as? JsonArray) ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)
                ?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it != "null" }
        }
    }

    /** First message-level model name: `modelName` then `models[0]`. */
    private fun parseModel(obj: JsonObject): String? {
        firstString(obj, "modelName", "model_name", "model")?.let { return it }
        val models = obj["models"] as? JsonArray ?: return null
        return models.firstNotNullOfOrNull { element ->
            (element as? JsonPrimitive)?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it != "null" }
        }
    }

    private fun looksLikeMessage(obj: JsonObject): Boolean =
        obj.keys.any { it in ROLE_KEYS } || obj.keys.any { it in CONTENT_KEYS } ||
            obj.keys.any { it in REASONING_KEYS } || obj.keys.any { it in PHASE_KEYS }

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
        when (value) {
            is JsonArray -> return value.joinToString("\n") { extractText(it).orEmpty() }
                .takeIf { it.isNotBlank() }
            is JsonObject -> {
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
            // Numbers, booleans and strings all surface their literal; JsonNull's
            // content is the string "null", which the filter below drops.
            is JsonPrimitive -> return value.content
                .takeIf { it.isNotEmpty() && it != "null" }
            else -> {}
        }
        return null
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

    private fun parseAttachments(obj: JsonObject): List<QwenAttachment> {
        for (key in ATTACHMENT_KEYS) {
            val array = obj[key] as? JsonArray ?: continue
            val attachments = array.filterIsInstance<JsonObject>().mapNotNull { item ->
                val name = firstString(item, "name", "file_name", "filename", "title")
                val url = firstString(item, "url", "link")
                if (name == null && url == null) null else QwenAttachment(name, url)
            }
            if (attachments.isNotEmpty()) return attachments
        }
        return emptyList()
    }

    /** Trim-aware, insertion-ordered dedup shared by the fragment dispatch. */
    private fun appendUnique(target: MutableList<String>, candidate: String?) {
        if (candidate == null) return
        val trimmed = candidate.trim()
        if (trimmed.isEmpty() || trimmed in target) return
        target.add(trimmed)
    }

    private companion object {
        val CONTAINER_KEYS = arrayOf(
            "data", "conversations", "chats", "history", "chat_history",
            "items", "list",
        )
        val MESSAGE_ARRAY_KEYS = arrayOf(
            "messages", "turns", "list", "chat_messages", "message_list", "messageList",
            "chatMessages", "message_chain", "messageChain", "msgs", "history", "items",
        )
        val TITLE_KEYS = arrayOf(
            "title", "name", "chat_name", "topic", "conversation_name", "summary",
        )
        val ID_KEYS = arrayOf(
            "id", "conversation_id", "conversationId", "uuid", "chat_id", "chatId",
            "session_id", "thread_id",
        )
        val CONVERSATION_DATE_KEYS = arrayOf(
            "updated_at", "created_at", "update_time", "create_time", "updatedAt",
            "createdAt", "date", "timestamp",
        )
        val ROLE_KEYS = arrayOf("role", "sender", "from", "author", "participant")
        val CONTENT_KEYS = arrayOf(
            "content", "message", "text", "markdown", "body", "msg", "output",
        )
        val REASONING_KEYS = arrayOf(
            "reasoning_content", "reasoningContent", "reasoning", "thinking",
            "thought", "thoughts", "think", "deepSeekThink", "deep_seek_think",
        )
        val MESSAGE_TIME_KEYS = arrayOf(
            "created_at", "createdAt", "create_time", "timestamp", "time", "date",
            "update_time",
        )
        val ATTACHMENT_KEYS = arrayOf("attachments", "files", "file_list", "files_list")
        val PHASE_KEYS = arrayOf("phase", "content_list")
    }
}
