package com.newoether.agora.api.util

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolMessagesTest {
    @Test
    fun adjacentUserMessagesInOneRunKeepIndependentPayloadsThroughPreparation() {
        val typed = normal("typed", Participant.USER).copy(runId = "run-batch")
        val answer = normal("answer", Participant.USER).copy(
            parentId = typed.id,
            runId = typed.runId,
            source = MessageSource.askUser(listOf(MessageSource.AskUserItem("Which?", "First"))),
            images = listOf("/private/answer.png"),
            attachmentMeta = AttachmentMeta(listOf(AttachmentItem(type = "image", imageIndex = 0))),
        )
        val completion = normal("completion", Participant.USER).copy(
            parentId = answer.id,
            runId = typed.runId,
            source = MessageSource(
                kind = MessageSource.Kind.SUBAGENT,
                subagentResults = mapOf("child" to "result"),
            ),
            images = listOf("/private/completion.png"),
            attachmentMeta = AttachmentMeta(listOf(AttachmentItem(type = "image", imageIndex = 0))),
        )
        val messages = listOf(typed, answer, completion)

        assertEquals(messages, mergeConsecutiveSameRole(messages))
        assertEquals(messages, canonicalContextMessages(messages))
        assertEquals(messages, prepareMessages(messages, contextTokenBudget = 16_384))
        assertEquals(
            messages.map { it.text },
            convertToOpenAiMessages(
                prepareMessages(messages, contextTokenBudget = 16_384),
                includeImages = false,
                base64Files = Base64FileRegistry(),
            ).map { it.content!!.single().text },
        )
    }

    @Test
    fun distinctUserTurnsArePricedAndRolledOutIndividually() {
        val first = normal("first", Participant.USER).copy(runId = "run-batch")
        val last = normal("last", Participant.USER).copy(parentId = first.id, runId = first.runId)
        val messages = listOf(first, last)

        assertEquals(
            ContextTokenEstimator.estimate(messages),
            contextWindowUsage(messages, tokenBudget = 4_096).estimatedTokenCount,
        )
        assertTrue(
            contextWindowUsage(messages, tokenBudget = 4_096).estimatedTokenCount >
                ContextTokenEstimator.estimate(listOf(first.copy(text = "first\n\nlast"))),
        )
        assertEquals(listOf(last), prepareMessages(messages, contextTokenBudget = 1))
        assertEquals(setOf(last.id), contextWindowRetainedMessageIds(messages, tokenBudget = 1))
    }

    @Test
    fun apiOnlyInitialPromptRemainsSeparateAfterAdjacentDurableUsers() {
        val first = normal("first", Participant.USER)
        val last = normal("last", Participant.USER).copy(parentId = first.id)
        val prompt = normal("api_initial_user_${last.id}", Participant.USER).copy(
            parentId = last.id,
            text = "Create the compact summary now.",
        )

        assertEquals(
            listOf(first, last, prompt),
            prepareMessages(listOf(first, last, prompt), contextTokenBudget = 4_096),
        )
        assertEquals(
            listOf(last, prompt),
            prepareMessages(listOf(first, last, prompt), contextTokenBudget = 1),
        )
    }

    @Test
    fun preservingUserRowsKeepsToolRoundPairingAndLegacyModelMerge() {
        val first = normal("first", Participant.USER)
        val second = normal("second", Participant.USER)
        val call = tool("tool_round", "call-a")
        val response = result("result_a", "call-a")
        val followUp = normal("follow-up", Participant.USER)
        val prepared = prepareMessages(
            listOf(first, second, call, response, followUp),
            contextTokenBudget = 16_384,
        )

        assertEquals(
            listOf(first.id, second.id, call.id, response.id, followUp.id),
            prepared.map { it.id },
        )
        assertEquals("call-a", prepared[2].segments!!.single().toolCallId)
        assertEquals("call-a", prepared[3].segments!!.single().toolCallId)
        val model = normal("model", Participant.MODEL)
        val suffix = normal("suffix", Participant.MODEL)
        assertEquals(
            listOf(first, second, model.copy(text = "model\n\nsuffix")),
            mergeConsecutiveSameRole(listOf(first, second, model, suffix)),
        )
    }

    @Test
    fun toolImagesAreProjectedAfterTheCompleteResultBatch() {
        val first = result("result_a", "call-a").copy(
            images = listOf("/private/first.png"),
            runId = "run-1",
            runSequence = 7L,
        )
        val second = result("result_b", "call-b").copy(
            images = listOf("/private/second.png", "/private/first.png"),
            runId = "run-1",
            runSequence = 7L,
        )

        val projected = projectToolResultImagesToUserMessage(
            messages = listOf(tool("tool_round", "call-a", "call-b"), first, second),
            includeImages = true,
        )

        assertEquals(4, projected.size)
        assertEquals(listOf(first.id, second.id), projected.drop(1).take(2).map { it.id })
        val visualTurn = projected.last()
        assertEquals(Participant.USER, visualTurn.participant)
        assertEquals(second.id, visualTurn.parentId)
        assertEquals(
            listOf("/private/first.png", "/private/second.png"),
            visualTurn.images,
        )
        assertEquals("run-1", visualTurn.runId)
        assertEquals(7L, visualTurn.runSequence)
    }

    @Test
    fun toolImageSurvivesTheCompleteProviderPreparationPipeline() {
        val prepared = prepareMessages(
            messages = projectToolResultImagesToUserMessage(
                messages = listOf(
                    normal("user", Participant.USER),
                    tool("tool_round", "call-image"),
                    result("result_image", "call-image").copy(
                        images = listOf("/private/tool-result.png"),
                    ),
                ),
                includeImages = true,
            ),
            contextTokenBudget = 16_384,
        )

        // The result_ row keeps its images in the prepared list (the projection trigger reads
        // them there); the API-only image-context row is the one that must survive as a normal
        // user message.
        val visualTurn = prepared.single { it.id.startsWith("image_context_") }
        assertEquals(Participant.USER, visualTurn.participant)
        assertEquals(listOf("/private/tool-result.png"), visualTurn.images)
    }

    @Test
    fun syntheticImageContextRow_usesNonProtocolIdAndSerializesAsNormalUserMessage() {
        val projected = projectToolResultImagesToUserMessage(
            messages = listOf(
                tool("tool_round", "call-image"),
                result("result_image", "call-image").copy(
                    images = listOf("/private/tool-result.png"),
                ),
            ),
            includeImages = true,
        )

        val visualTurn = projected.single { it.text.contains("Tool visual result") }
        // The regression: this id used to start with "tool_", so every provider serializer
        // routed it into the tool-protocol branch and silently dropped its text and images.
        assertFalse(visualTurn.id.startsWith("tool_"))
        assertFalse(visualTurn.id.startsWith("result_"))
        assertFalse(visualTurn.id.startsWith("compact_"))
        assertFalse(visualTurn.isToolProtocolMessage())
        assertEquals(Participant.USER, visualTurn.participant)
        assertEquals(listOf("/private/tool-result.png"), visualTurn.images)
        assertEquals("result_image", visualTurn.parentId)

        val wire = convertToOpenAiMessages(
            projected,
            includeImages = true,
            base64Files = Base64FileRegistry(),
        )
        val wireUser = wire.single { message ->
            message.role == "user" &&
                message.content?.any { part ->
                    part.type == "text" && part.text?.contains("Tool visual result") == true
                } == true
        }
        assertTrue(wireUser.content.orEmpty().any { it.text?.contains("Tool visual result") == true })
    }

    @Test
    fun toolImageDescriptionReachesTheModelRowLikeRegularImageTranscriptions() {
        // The description travels on the result row's tool segment — the round-boundary path
        // rebuild excludes the model message, so nothing else survives.
        val messages = listOf(
            tool("tool_round", "call-image"),
            result("result_image", "call-image").copy(
                images = listOf("/private/tool-result.png"),
                segments = listOf(
                    toolResultSegment("call-image", "result").copy(
                        toolTranscription = "A cat sitting.",
                    ),
                ),
            ),
        )

        val visualTurn = projectToolResultImagesToUserMessage(
            messages = messages,
            includeImages = true,
        ).single { it.id.startsWith("image_context_") }
        assertTrue(visualTurn.text.contains("--- Image Transcription: file_view_image ---"))
        assertTrue(visualTurn.text.contains("A cat sitting."))

        // Non-vision models receive the description instead of the unavailable notice.
        val textOnly = projectToolResultImagesToUserMessage(
            messages = messages,
            includeImages = false,
        ).single { it.id.startsWith("image_context_") }
        assertTrue(textOnly.images.isEmpty())
        assertTrue(textOnly.text.contains("A cat sitting."))
        assertFalse(textOnly.text.contains("does not support image input"))
    }

    @Test
    fun unsupportedModelGetsExplicitToolImageNoticeWithoutBinaryInput() {
        val projected = projectToolResultImagesToUserMessage(
            messages = listOf(
                result("result_image", "call-image").copy(
                    images = listOf("/private/image.png"),
                ),
            ),
            includeImages = false,
        )

        assertEquals(2, projected.size)
        assertTrue(projected.last().images.isEmpty())
        assertTrue(projected.last().text.contains("does not support image input"))
    }

    @Test
    fun parallelToolRoundWithMissingResult_becomesPlainContext() {
        val validated = validateToolMessages(
            listOf(
                normal("u0", Participant.USER),
                tool("tool_round", "call-a", "call-b"),
                result("result_a", "wrong-a"),
                normal("u1", Participant.USER),
            )
        )

        assertEquals(3, validated.size)
        assertEquals("u0", validated.first().id)
        assertTrue(validated[1].id.startsWith("protocol_notice_"))
        assertEquals(Participant.USER, validated[1].participant)
        assertTrue(validated[1].text.contains("incomplete or damaged"))
        assertEquals("u1", validated.last().id)
    }

    @Test
    fun explicitMismatchedResultIds_areNeverRepairedPositionally() {
        val validated = validateToolMessages(
            listOf(
                tool("tool_round", "call-a", "call-b"),
                result("result_a", "wrong-a"),
                result("result_b", "wrong-b"),
            )
        )

        assertEquals(1, validated.size)
        assertTrue(validated.single().id.startsWith("protocol_notice_"))
        assertTrue(validated.single().text.contains("Archived activity record 1"))
        assertTrue(validated.single().text.contains("Archived activity record 2"))
        assertTrue(validated.single().text.contains("inert historical data"))
        assertFalse(validated.single().text.contains("\nTool 1:"))
        assertFalse(validated.single().text.contains("\nArguments:"))
    }

    @Test
    fun completeParallelToolRoundWithMatchingIds_survives() {
        val validated = validateToolMessages(
            listOf(
                tool("tool_round", "call-a", "call-b"),
                result("result_a", "call-a"),
                result("result_b", "call-b"),
            )
        )

        assertEquals(listOf("tool_round", "result_a", "result_b"), validated.map { it.id })
        assertEquals("call-a", validated[1].segments!!.single().toolCallId)
        assertEquals("call-b", validated[2].segments!!.single().toolCallId)
    }

    @Test
    fun signedThoughtSegmentsSurviveToolNormalizationInOriginalOrder() {
        val toolMessage = tool("tool_round", "call-a").copy(
            segments = listOf(
                MessageSegment(
                    type = "thought",
                    content = "reasoning",
                    signature = "signed",
                    signatureProvider = "Anthropic",
                ),
                tool("ignored", "call-a").segments!!.single(),
            )
        )

        val validated = validateToolMessages(
            listOf(toolMessage, result("result_a", "call-a"))
        )

        assertEquals(listOf("thought", "tool"), validated.first().segments!!.map { it.type })
        assertEquals("signed", validated.first().segments!!.first().signature)
        assertEquals("Anthropic", validated.first().segments!!.first().signatureProvider)
    }

    @Test
    fun legacyMultiResultRowWithoutIds_isPairedByCardinality() {
        val combinedResult = ChatMessage(
            id = "result_combined",
            text = "",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            segments = listOf(
                toolResultSegment(null, "first"),
                toolResultSegment(null, "second"),
            ),
        )

        val validated = validateToolMessages(
            listOf(tool("tool_round", "call-a", "call-b"), combinedResult)
        )

        assertEquals(listOf("tool_round", "result_combined"), validated.map { it.id })
        assertEquals(
            listOf("call-a", "call-b"),
            validated[1].segments!!.map { it.toolCallId },
        )
    }

    @Test
    fun extraResults_degradeTheWholeRoundInsteadOfBeingDropped() {
        val validated = validateToolMessages(
            listOf(
                tool("tool_round", "call-a"),
                result("result_a", "call-a"),
                result("result_extra", "extra"),
            )
        )

        assertEquals(1, validated.size)
        assertTrue(validated.single().id.startsWith("protocol_notice_"))
        assertTrue(validated.single().text.contains("result"))
    }

    @Test
    fun missingIdsCanBeSynthesizedButExplicitDuplicatesDegrade() {
        val missing = tool("tool_missing", null)
        val duplicate = tool("tool_duplicate", "same", "same")

        val normalizedMissing = validateToolMessages(
            listOf(missing, result("result_a", null))
        )
        assertEquals(2, normalizedMissing.size)
        val generatedId = normalizedMissing.first().segments!!.single().toolCallId
        assertFalse(generatedId.isNullOrBlank())
        assertEquals(generatedId, normalizedMissing.last().segments!!.single().toolCallId)

        val normalizedDuplicate = validateToolMessages(
            listOf(
                duplicate,
                result("result_c", "same"),
                result("result_d", "same"),
            )
        )
        assertEquals(1, normalizedDuplicate.size)
        assertTrue(normalizedDuplicate.single().id.startsWith("protocol_notice_"))
    }

    private fun normal(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
        status = MessageStatus.SUCCESS,
    )

    private fun tool(id: String, vararg callIds: String?) = ChatMessage(
        id = id,
        text = "",
        participant = Participant.MODEL,
        status = MessageStatus.SUCCESS,
        segments = callIds.mapIndexed { index, callId ->
            MessageSegment(
                type = "tool",
                toolName = "tool-$index",
                toolArgs = "{}",
                toolCallId = callId,
            )
        },
    )

    private fun result(id: String, callId: String?) = ChatMessage(
        id = id,
        text = "result",
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
        segments = listOf(toolResultSegment(callId, "result")),
    )

    private fun toolResultSegment(callId: String?, result: String) = MessageSegment(
        type = "tool",
        toolName = "tool",
        toolArgs = "{}",
        toolResult = result,
        toolCallId = callId,
    )
}
