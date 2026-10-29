package com.newoether.agora.data.local

import androidx.room.Query

data class EmbeddingModelCount(
    val modelId: String,
    val count: Int,
)

internal const val CITATION_MESSAGE_PAGE_QUERY = """
    SELECT m.*
    FROM messages m
    CROSS JOIN conversations c
    WHERE m.conversationId = c.id
      AND c.taskId IS NULL
      AND c.parentConversationId IS NULL
      AND m.toolCallJson LIKE '%"type":"citation"%'
      AND m.participant = 'MODEL'
      AND substr(m.id, 1, 5) != 'tool_'
      AND substr(m.id, 1, 7) != 'result_'
      AND m.id > :afterId
    ORDER BY m.id ASC
    LIMIT :pageSize
"""

data class KeywordSearchRow(
    val id: String,
    val timestamp: Long,
    val directMatch: Boolean,
    val toolCallJson: String?,
)

// Both ranking paths and final hydration share the same history visibility boundary.
internal const val HISTORY_MESSAGE_ELIGIBILITY = """
    c.taskId IS NULL AND c.parentConversationId IS NULL
    AND m.participant IN ('USER', 'MODEL') AND m.text != ''
    AND substr(m.id, 1, 5) != 'tool_'
    AND substr(m.id, 1, 7) != 'result_'
    AND substr(m.id, 1, 8) != 'compact_'
"""

// LIMIT is inside the subquery: even a miss releases the read connection after one short page.
internal const val KEYWORD_MESSAGE_PROJECTION = """
    SELECT m.id, m.timestamp,
        CASE WHEN $HISTORY_MESSAGE_ELIGIBILITY THEN
            CASE WHEN :literalQuery IS NOT NULL THEN
                (instr(m.text, :literalQuery) > 0 OR instr(c.title, :literalQuery) > 0)
            ELSE (m.text LIKE '%' || :query || '%' ESCAPE '\' OR
                  c.title LIKE '%' || :query || '%' ESCAPE '\') END
            ELSE 0 END AS directMatch,
        CASE WHEN $HISTORY_MESSAGE_ELIGIBILITY
            AND m.participant = 'MODEL'
            AND instr(m.toolCallJson, '"type":"citation"') > 0
            THEN m.toolCallJson ELSE NULL END AS toolCallJson
    FROM (
"""
internal const val KEYWORD_MESSAGE_FIRST_PAGE = KEYWORD_MESSAGE_PROJECTION + """
        SELECT id, timestamp FROM messages
        ORDER BY timestamp DESC, id DESC LIMIT :pageSize
    ) page CROSS JOIN messages m ON m.id = page.id
    LEFT JOIN conversations c ON m.conversationId = c.id
    ORDER BY page.timestamp DESC, page.id DESC
"""
internal const val KEYWORD_MESSAGE_NEXT_PAGE = KEYWORD_MESSAGE_PROJECTION + """
        SELECT id, timestamp FROM messages
        WHERE (timestamp, id) < (:beforeTimestamp, :beforeId)
        ORDER BY timestamp DESC, id DESC LIMIT :pageSize
    ) page CROSS JOIN messages m ON m.id = page.id
    LEFT JOIN conversations c ON m.conversationId = c.id
    ORDER BY page.timestamp DESC, page.id DESC
"""

/** Search eligibility and bounded projections inherited by the sole [ChatDao]. */
interface ChatSearchDao {
    @Query(KEYWORD_MESSAGE_FIRST_PAGE)
    suspend fun getKeywordSearchFirstPage(
        query: String,
        pageSize: Int,
        literalQuery: String? = null,
    ): List<KeywordSearchRow>

    @Query(KEYWORD_MESSAGE_NEXT_PAGE)
    suspend fun getKeywordSearchNextPage(
        query: String,
        beforeTimestamp: Long,
        beforeId: String,
        pageSize: Int,
        literalQuery: String? = null,
    ): List<KeywordSearchRow>

    // Keep messages first so each page seeks its primary key instead of sorting the corpus again.
    @Query(CITATION_MESSAGE_PAGE_QUERY)
    suspend fun getMessagesWithCitationSegmentsPage(
        afterId: String,
        pageSize: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT e.id, e.messageId, e.embedding, e.dimension
        FROM embeddings e
        CROSS JOIN messages m
        CROSS JOIN conversations c
        WHERE e.messageId = m.id
          AND m.conversationId = c.id
          AND e.modelId = :modelId
          AND e.id > :afterId
          AND $HISTORY_MESSAGE_ELIGIBILITY
          AND LENGTH(m.text) >= :minimumTextLength
        ORDER BY e.id
        LIMIT :limit
        """
    )
    suspend fun getEmbeddingSearchPage(
        modelId: String,
        afterId: Long,
        minimumTextLength: Int,
        limit: Int,
    ): List<EmbeddingSearchRow>

    @Query("SELECT COUNT(*) FROM embeddings e INNER JOIN messages m ON e.messageId = m.id INNER JOIN conversations c ON m.conversationId = c.id WHERE e.modelId = :modelId AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'")
    suspend fun getEmbeddingCountByModel(modelId: String): Int

    @Query(
        """
        SELECT e.modelId AS modelId, COUNT(*) AS count
        FROM embeddings e
        INNER JOIN messages m ON e.messageId = m.id
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE e.modelId IN (:modelIds)
          AND c.taskId IS NULL
          AND m.participant IN ('USER', 'MODEL')
          AND m.text != ''
          AND m.id NOT LIKE 'tool_%'
          AND m.id NOT LIKE 'result_%'
          AND m.id NOT LIKE 'compact_%'
        GROUP BY e.modelId
        """
    )
    suspend fun getEmbeddingCountsByModels(modelIds: List<String>): List<EmbeddingModelCount>

    @Query("SELECT COUNT(*) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'")
    suspend fun getIndexableMessageCount(): Int

    @Query(
        """
        SELECT m.id, m.text FROM messages m INNER JOIN conversations c ON m.conversationId = c.id
        WHERE c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != ''
          AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'
          AND (:afterId IS NULL OR m.id > :afterId)
        ORDER BY m.id LIMIT :limit
        """,
    )
    suspend fun getSearchableMessagesPage(afterId: String?, limit: Int): List<IndexableMessage>

    @Query(
        """
        SELECT m.id, m.text
        FROM messages m
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE c.taskId IS NULL
          AND m.participant IN ('USER', 'MODEL')
          AND m.text != ''
          AND m.id NOT LIKE 'tool_%'
          AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'
          AND NOT EXISTS (
              SELECT 1 FROM embeddings e
              WHERE e.messageId = m.id AND e.modelId = :modelId
          )
          AND (:afterId IS NULL OR m.id > :afterId)
        ORDER BY m.id
        LIMIT :limit
        """
    )
    suspend fun getUnembeddedMessagesPage(
        modelId: String,
        afterId: String?,
        limit: Int,
    ): List<IndexableMessage>

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id IN (:ids) AND $HISTORY_MESSAGE_ELIGIBILITY")
    suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id = :messageId AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%')")
    suspend fun isMessageSearchable(messageId: String): Boolean

    @Query("SELECT * FROM conversations WHERE id = :conversationId AND taskId IS NULL AND (parentConversationId IS NULL OR parentConversationId = :ownerConversationId)")
    suspend fun getSearchableConversation(conversationId: String, ownerConversationId: String? = null): ChatEntity?


    @Query("SELECT COUNT(*) FROM conversations WHERE taskId IS NULL AND parentConversationId IS NULL")
    suspend fun getSearchableConversationCount(): Int

    @Query(
        """
        SELECT *
        FROM conversations
        WHERE taskId IS NULL AND parentConversationId IS NULL
        ORDER BY lastUpdated ASC, id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSearchableConversationsPageAscending(
        offset: Int,
        limit: Int,
    ): List<ChatEntity>

    @Query(
        """
        SELECT *
        FROM conversations
        WHERE taskId IS NULL AND parentConversationId IS NULL
        ORDER BY lastUpdated DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSearchableConversationsPageDescending(
        offset: Int,
        limit: Int,
    ): List<ChatEntity>
}
