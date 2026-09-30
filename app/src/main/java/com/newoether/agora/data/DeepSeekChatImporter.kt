package com.newoether.agora.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.UUID

/**
 * Imports chat history from a DeepSeek export ("Settings → Export chat history").
 *
 * The official export is a single JSON document whose top-level `data` array holds
 * one entry per conversation; each conversation carries a linear `list` of messages
 * with `role`, `content`, and a unix-epoch `time`. DeepThink reasoning arrives under
 * `deepSeekThink` (older exports also used `thinking`), so all known spellings are
 * accepted. Attachments are metadata-only in the export and cannot be recovered, so
 * they are reported through the preview but never imported.
 *
 * The parser is deliberately tolerant about container shape (top-level array,
 * `data`, or `conversations`) so re-saved or tool-converted exports keep working.
 */
class DeepSeekChatImporter {

    @Serializable
    data class DeepSeekExport(
        val data: List<DeepSeekConversation> = emptyList(),
        val conversations: List<DeepSeekConversation> = emptyList(),
    )

    @Serializable
    data class DeepSeekConversation(
        val id: String = "",
        val title: String = "",
        /** Conversation creation time, unix seconds. */
        val date: Double = 0.0,
        val list: List<DeepSeekMessage> = emptyList(),
        val messages: List<DeepSeekMessage> = emptyList(),
    )

    @Serializable
    data class DeepSeekMessage(
        val role: String = "",
        val content: String = "",
        /** Unix seconds of this message. */
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
     * Parses a DeepSeek export stream. Rejection is by exception: callers surface the
     * localized message. Both raw arrays and wrapped objects are accepted.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun extractAndParse(openStream: () -> InputStream): Result<List<DeepSeekConversation>> {
        return try {
            openStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val text = reader.readText()
                if (text.isBlank()) {
                    return Result.failure(Exception("File is empty"))
                }
                val trimmed = text.trim()
                val conversations = if (trimmed.startsWith("[")) {
                    jsonParser.decodeFromString<List<DeepSeekConversation>>(text)
                } else {
                    val export = jsonParser.decodeFromString<DeepSeekExport>(text)
                    export.data.ifEmpty { export.conversations }
                }
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

    /** DeepSeek epochs are seconds; tolerate milliseconds and missing values. */
    private fun toEpochMillis(primary: Double, fallback: Double?): Long {
        val candidate = if (primary > 0) primary else (fallback ?: 0.0)
        if (candidate <= 0) return System.currentTimeMillis()
        return if (candidate > 1_000_000_000_000.0) candidate.toLong()
        else (candidate * 1000.0).toLong()
    }
}
