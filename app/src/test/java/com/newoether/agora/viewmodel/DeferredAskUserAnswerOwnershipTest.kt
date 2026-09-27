package com.newoether.agora.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ask_user controller and the send queues are process-wide, so the process owns one delivery
 * for non-blocking answers. One Send of such answers must become exactly one queued user message.
 */
class DeferredAskUserAnswerOwnershipTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun oneSendOfNonBlockingAnswersQueuesOneMessage() {
        val askUser = AskUserController()
        val registry = ConversationStateRegistry()
        DeferredAskUserAnswerDelivery(
            askUser = askUser,
            registry = registry,
            scope = scope,
            conversationModelId = { "model" },
            fallbackModelId = { null },
            ioDispatcher = Dispatchers.Unconfined,
        ).start()
        val first = askUser.open("conversation", "First?", listOf("A", "B"), allowMultiple = false, blocking = false)
        val second = askUser.open("conversation", "Second?", listOf("C", "D"), allowMultiple = false, blocking = false)

        askUser.submitAll(
            listOf(
                first.id to AskUserController.Answer(listOf("A"), answered = true),
                second.id to AskUserController.Answer(listOf("D"), answered = true),
            ),
        )

        val queued = registry.getOrCreate("conversation").queuedSends.value
        assertEquals(1, queued.size)
        assertEquals("model", queued.single().modelId)
        assertEquals(
            listOf(first to "A", second to "D").joinToString("\n\n") { (request, choice) ->
                AskUserController.deferredAnswerText(request, AskUserController.Answer(listOf(choice), answered = true))
            },
            queued.single().text,
        )
    }
}
