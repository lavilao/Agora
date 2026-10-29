package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.AskUserController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class,
    qualifiers = "w412dp-h800dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedToolsGeometryRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val agents = (0..8).map {
        SubagentSummary("child-$it", "Worker $it investigation", "model", "parent", "idle")
    }

    @Test fun parentTodoThenChildWithoutTodoReleasesGhostSlotWithRealEmptyInteraction() {
        var selected by mutableStateOf("parent")
        var inset = 0f
        compose.setContent {
            inset = with(LocalDensity.current) { 12.dp.toPx() }
            MaterialTheme {
                ConversationToolsRow(selected,
                    if (selected == "parent") listOf(ConversationTodoItem("Task")) else emptyList(),
                    agents, {}, {}, modifier = Modifier.width(412.dp), familyId = "parent",
                ) { modifier, height, clearance, _, _, _ -> EmptyProductionInteraction(modifier, height, clearance) }
            }
        }
        assertTrue(slotWidth("todo") > 0f)
        assertEquals(0f, slotWidth("interaction"), 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "child-0" }
        frame(48)
        assertTrue("Outgoing Todo retains its full slot while fading", slotWidth("todo") > 0f)
        frame(800)
        assertEquals("Disposed Todo must measure zero even though it emits no modifier", 0f, slotWidth("todo"), 0f)
        assertEquals(0f, slotWidth("interaction"), 0f)
        assertEquals("Initial logical start remains at zero", 0f, scrollValue(), 0f)
        val first = compose.onNodeWithTag("tools-slot:subagent:parent:child-0").fetchSemanticsNode()
        val row = compose.onNodeWithTag("conversation-tools-row").fetchSemanticsNode()
        assertEquals("Overflow has only its ordinary outer inset", inset, first.boundsInRoot.left - row.boundsInRoot.left, 1f)
        val identity = first.id
        compose.runOnIdle { selected = "child-1" }
        frame(400)
        assertEquals(identity, compose.onNodeWithTag("tools-slot:subagent:parent:child-0").fetchSemanticsNode().id)
        assertEquals(0f, slotWidth("todo"), 0f)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
    }

    @Test fun maximumScrollKeepsTheLastCapsuleFixedThroughTodoDisposalAndReturn() {
        var selected by mutableStateOf("parent")
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow(selected,
                    if (selected == "parent") listOf(ConversationTodoItem("Task")) else emptyList(),
                    agents, {}, {}, modifier = Modifier.width(412.dp), familyId = "parent",
                ) { modifier, height, clearance, _, _, _ -> EmptyProductionInteraction(modifier, height, clearance) }
            }
        }
        val row = compose.onNodeWithTag("conversation-tools-row")
        row.performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
        compose.waitForIdle()
        val range = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.maxValue() > 0f)
        assertEquals(range.maxValue(), range.value(), 0f)
        val capsule = compose.onNodeWithTag("tools-slot:subagent:parent:child-8")
        val identity = capsule.fetchSemanticsNode().id
        val left = capsule.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        for (owner in listOf("child-8", "parent")) {
            compose.runOnIdle { selected = owner }
            repeat(50) { index ->
                frame(0)
                val node = capsule.fetchSemanticsNode()
                assertEquals(identity, node.id)
                assertEquals("Maximum-scroll anchor for $owner at frame $index", left, node.boundsInRoot.left, 1f)
            }
        }
    }

    @Test fun pointerTapAtMaximumScrollKeepsEveryVisibleCapsuleFixed() {
        assertPointerMaximumScroll(reduced = false)
    }

    @Test fun reducedMotionPointerTapAtMaximumScrollKeepsEveryVisibleCapsuleFixed() {
        assertPointerMaximumScroll(reduced = true)
    }

    private fun assertPointerMaximumScroll(reduced: Boolean) {
        var selected by mutableStateOf("parent")
        val family = (0..15).map {
            SubagentSummary("child-$it", "Worker $it investigation", "model", "parent", "idle")
        }
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme {
                    ConversationToolsRow(selected,
                        if (selected == "parent") listOf(ConversationTodoItem("Task")) else emptyList(),
                        family, { selected = it }, {}, modifier = Modifier.width(412.dp), familyId = "parent",
                    ) { modifier, height, clearance, _, _, _ -> EmptyProductionInteraction(modifier, height, clearance) }
                }
            }
        }
        val row = compose.onNodeWithTag("conversation-tools-row")
        row.performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
        compose.waitForIdle()
        val range = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(range.maxValue(), range.value(), 0f)
        val viewport = row.fetchSemanticsNode().boundsInRoot
        val visible = family.mapNotNull { agent ->
            val tag = "tools-slot:subagent:parent:${agent.id}"
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            if (node.boundsInRoot.right > viewport.left && node.boundsInRoot.left < viewport.right) {
                Triple(tag, node.id, node.boundsInRoot.left)
            } else null
        }
        assertTrue(visible.isNotEmpty())
        val todo = compose.onNodeWithTag("tools-slot:todo").fetchSemanticsNode().boundsInRoot
        assertTrue("Todo is fully outside the tapped viewport", todo.right <= viewport.left)
        compose.mainClock.autoAdvance = false
        repeat(2) { cycle ->
            val tap = compose.onNodeWithText("Worker 15 investigation").fetchSemanticsNode().boundsInRoot.center
            row.performTouchInput { click(tap - viewport.topLeft) }
            repeat(60) { index ->
                frame(0)
                assertEquals("Pointer click selects the real capsule", "child-15", selected)
                visible.forEach { (tag, identity, left) ->
                    val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                    assertEquals(identity, node.id)
                    assertEquals("Pointer cycle $cycle exit frame $index for $tag", left, node.boundsInRoot.left, 1f)
                }
            }
            compose.runOnIdle { selected = "parent" }
            repeat(60) { index ->
                frame(0)
                visible.forEach { (tag, identity, left) ->
                    val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                    assertEquals(identity, node.id)
                    assertEquals("Pointer cycle $cycle return frame $index for $tag", left, node.boundsInRoot.left, 1f)
                }
            }
        }
    }

    @Test fun initialEmptyInteractionAndTodoLeaveNoSpaceBeforeOverflowingFamily() {
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow("parent", emptyList(), agents, {}, {}, modifier = Modifier.width(412.dp)) {
                        modifier, height, clearance, _, _, _ -> EmptyProductionInteraction(modifier, height, clearance)
                }
            }
        }
        assertEquals(0f, slotWidth("interaction"), 0f)
        assertEquals(0f, slotWidth("todo"), 0f)
        assertEquals(0f, scrollValue(), 0f)
    }

    @Test fun interactionRevealWinsOverInsertionAndExpansionAnchorTravel() {
        var waiting by mutableStateOf(false)
        var expanded by mutableStateOf(false)
        var token by mutableStateOf<Any?>(null)
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow("parent", emptyList(), agents, {}, {}, modifier = Modifier.width(412.dp),
                    hasInteraction = waiting, interactionRevealKey = token,
                ) { modifier, height, _, _, _, _ ->
                    if (waiting) Box(modifier.width(if (expanded) 388.dp else 180.dp).height(56.dp))
                }
            }
        }
        compose.onNodeWithText("Worker 5 investigation").performScrollTo()
        val before = scrollValue()
        assertTrue(before > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { waiting = true; token = 1 }
        frame(112)
        val during = scrollValue()
        assertTrue("Reveal animates rather than jumping: $before -> $during", during > 0f && during < before)
        frame(600)
        assertEquals("Late insertion geometry cannot restore the old anchor", 0f, scrollValue(), 0f)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Worker 5 investigation").performScrollTo()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded = true; token = 2 }
        frame(112)
        assertTrue(scrollValue() > 0f)
        frame(600)
        assertEquals("An expansion event reveals the same start without another scroll owner", 0f, scrollValue(), 0f)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
    }

    @Test fun draggingCancelsRevealAndReleaseDoesNotReplayConsumedToken() {
        var token by mutableStateOf<Any?>(null)
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow("parent", emptyList(), agents, {}, {}, modifier = Modifier.width(412.dp),
                    interactionRevealKey = token)
            }
        }
        compose.onNodeWithText("Worker 5 investigation").performScrollTo()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { token = 1 }
        frame(64)
        val row = compose.onNodeWithTag("conversation-tools-row")
        row.performTouchInput { down(center); moveBy(Offset(-width * 0.2f, 0f)) }
        frame(32)
        val dragged = scrollValue()
        frame(160)
        assertEquals(dragged, scrollValue(), 1f)
        row.performTouchInput { advanceEventTime(200); up() }
        val released = scrollValue()
        frame(600)
        assertEquals("Releasing a gesture never replays the same event", released, scrollValue(), 1f)
    }

    @Test fun reducedMotionRevealsStartWithoutSpatialTween() {
        var token by mutableStateOf<Any?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                MaterialTheme {
                    ConversationToolsRow("parent", emptyList(), agents, {}, {}, modifier = Modifier.width(412.dp),
                        interactionRevealKey = token)
                }
            }
        }
        compose.onNodeWithText("Worker 5 investigation").performScrollTo()
        assertTrue(scrollValue() > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { token = 1 }
        frame(0)
        assertEquals(0f, scrollValue(), 0f)
    }

    @Composable private fun EmptyProductionInteraction(
        modifier: Modifier,
        height: (Float) -> Unit,
        clearance: (Float) -> Unit,
    ) {
        UserInteractionBar("parent", emptyList(), false, {}, {}, { _, _, _ -> }, height,
            emptySet(), { _, _ -> }, remember { QuestionDrafts() }, emptyMap(), { _, _ -> }, {},
            modifier = modifier, inSharedRow = true, onMessageInsetChanged = clearance)
    }

    private fun slotWidth(key: String): Float = compose.onNodeWithTag("tools-slot:$key")
        .fetchSemanticsNode().boundsInRoot.width

    private fun scrollValue(): Float = compose.onNodeWithTag("conversation-tools-row")
        .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun frame(millis: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }
}
