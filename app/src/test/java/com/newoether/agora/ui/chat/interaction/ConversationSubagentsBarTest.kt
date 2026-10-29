package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.SubagentFamilySnapshot
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.model.SubagentSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationSubagentsBarTest {
    @get:Rule val compose = createComposeRule()

    private fun agent(id: String, title: String = id, status: String = "idle") =
        SubagentSummary(id, title, "Provider:model", "parent", status)

    @Test fun idleAndCompletedUseBranchIconWhileFailureAndStopStayDistinct() {
        assertSame(Icons.Default.AccountTree, subagentStatusIcon("idle"))
        assertSame(Icons.Default.AccountTree, subagentStatusIcon("completed"))
        assertSame(Icons.Default.ErrorOutline, subagentStatusIcon("failed"))
        assertSame(Icons.Default.PauseCircleOutline, subagentStatusIcon("stopped"))
        assertNotSame(subagentStatusIcon("idle"), subagentStatusIcon("failed"))
        assertNotSame(subagentStatusIcon("idle"), subagentStatusIcon("stopped"))
        assertNotSame(subagentStatusIcon("failed"), subagentStatusIcon("stopped"))
    }

    @Test fun selectionColorsFadeWithoutReplacingCapsuleOrChangingBounds() = verifySelectionColors(false)

    @Test fun reducedMotionRetainsSelectionColorFades() = verifySelectionColors(true)

    private fun verifySelectionColors(reducedMotion: Boolean) {
        var selected by mutableStateOf(false)
        val normalBackground = Color(0xFF163955)
        val selectedBackground = Color(0xFF1F754B)
        val normalContent = Color(0xFFF5F5F5)
        val selectedContent = Color(0xFFFFCD45)
        val normalLabel = Color(0xFFB5C7D2)
        lateinit var label: String
        lateinit var view: android.view.View
        compose.setContent {
            view = LocalView.current
            label = androidx.compose.ui.res.stringResource(R.string.message_source_subagent)
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reducedMotion)) {
                MaterialTheme(colorScheme = lightColorScheme(
                    surfaceContainerHigh = normalBackground, secondaryContainer = selectedBackground,
                    onSurface = normalContent, onSecondaryContainer = selectedContent, onSurfaceVariant = normalLabel,
                )) {
                    ConversationSubagentCapsule(agent("child", "Worker"), selected, true, {},
                        Modifier.testTag("selection-capsule"))
                }
            }
        }
        val capsule = compose.onNodeWithTag("selection-capsule")
        val status = compose.onNodeWithTag("subagent-status:child", useUnmergedTree = true)
        val initialNode = capsule.fetchSemanticsNode()
        val initialSlot = status.fetchSemanticsNode()
        fun distance(a: Color, b: Color): Float = kotlin.math.abs(a.red - b.red) +
            kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)
        fun colors(): Pair<Color, Color> {
            val surface = capsule.fetchSemanticsNode().boundsInRoot
            val icon = status.fetchSemanticsNode().boundsInRoot
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                // Draw the real Compose View without PixelCopy's unsupported Robolectric redraw fence.
                compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
                val background = Color(bitmap.getPixel(surface.left.toInt() + 1, surface.center.y.toInt()))
                val foreground = (icon.top.toInt() until icon.bottom.toInt()).flatMap { y ->
                    (icon.left.toInt() until icon.right.toInt()).map { x -> Color(bitmap.getPixel(x, y)) }
                }.maxBy { distance(it, background) }
                return background to foreground
            } finally {
                bitmap.recycle()
            }
        }
        fun labelColor(): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
                SemanticsActions.GetTextLayoutResult,
            ) { it(layouts) }
            return layouts.single().layoutInput.style.color
        }
        fun assertColors(expectedBackground: Color, expectedContent: Color, expectedLabel: Color) {
            val (background, foreground) = colors()
            assertTrue("Background $background vs $expectedBackground", distance(background, expectedBackground) < 0.02f)
            assertTrue("Content $foreground vs $expectedContent", distance(foreground, expectedContent) < 0.02f)
            assertTrue("Label vs $expectedLabel", distance(labelColor(), expectedLabel) < 0.02f)
        }
        assertColors(normalBackground, normalContent, normalLabel)
        compose.mainClock.autoAdvance = false
        for (next in listOf(true, false)) {
            compose.runOnIdle { selected = next }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(112)
            compose.waitForIdle()
            val (background, foreground) = colors()
            val labelColor = labelColor()
            assertTrue("Background must be between endpoints", distance(background, normalBackground) > 0.02f &&
                distance(background, selectedBackground) > 0.02f)
            assertTrue("Content must be between endpoints", distance(foreground, normalContent) > 0.02f &&
                distance(foreground, selectedContent) > 0.02f)
            assertTrue("Label must be between endpoints", distance(labelColor, normalLabel) > 0.02f &&
                distance(labelColor, selectedContent) > 0.02f)
            val node = capsule.fetchSemanticsNode()
            assertEquals(initialNode.id, node.id)
            assertEquals(initialNode.boundsInRoot, node.boundsInRoot)
            assertEquals(next, node.config[SemanticsProperties.Selected])
            assertEquals(initialSlot.id, status.fetchSemanticsNode().id)
            assertEquals(initialSlot.boundsInRoot, status.fetchSemanticsNode().boundsInRoot)
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            assertColors(if (next) selectedBackground else normalBackground,
                if (next) selectedContent else normalContent, if (next) selectedContent else normalLabel)
        }
    }

    @Test fun ownerChangeFadesInertOutgoingRowAndSkipsIntermediateOwner() {
        var owner by mutableStateOf("a")
        var items by mutableStateOf(listOf(agent("a", "Worker A")))
        var height = 0f
        var selected: String? = null
        compose.setContent {
            MaterialTheme { ConversationToolsRow(owner, emptyList(), items, { selected = it }, { height = it }) }
        }
        compose.runOnIdle { }
        val fullHeight = height
        assertTrue(fullHeight > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner = "b"; items = listOf(agent("b", "Worker B")) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("Worker A").assertExists().assertIsNotEnabled().performClick()
        assertNull(selected)
        compose.onNodeWithText("Worker B").assertDoesNotExist()
        assertTrue("Outgoing lift follows fade: $height", height > 0f && height < fullHeight)
        compose.runOnIdle { owner = "c"; items = listOf(agent("c", "Worker C")) }
        // Exit fade/collapse precede incoming reservation/fade, with measured handoffs.
        compose.mainClock.advanceTimeUntil(timeoutMillis = 2_000) {
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            height == fullHeight
        }
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("Readiness must not restart the settled appearance", fullHeight, height, 0f)
        }
        compose.onNodeWithText("Worker A").assertDoesNotExist()
        compose.onNodeWithText("Worker B").assertDoesNotExist()
        compose.onNodeWithText("Worker C").assertExists().performClick()
        assertEquals("c", selected)
        assertEquals(fullHeight, height, 0.1f)
        compose.runOnIdle { items = listOf(agent("c", "Updated worker", "completed")) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Updated worker").assertExists()
        assertEquals("Status/title updates do not replay entrance", fullHeight, height, 0.1f)
    }

    @Test fun clearingRetainsOnlyDisabledPaintUntilLiftReachesZero() {
        var items by mutableStateOf(listOf(agent("child", "Worker")))
        var height = 0f
        compose.setContent { MaterialTheme { ConversationToolsRow("parent", emptyList(), items, {}, { height = it }) } }
        compose.runOnIdle { }
        val fullHeight = height
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { items = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("Worker").assertExists().assertIsNotEnabled()
        assertTrue(height > 0f && height < fullHeight)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Worker").assertDoesNotExist()
        assertEquals(0f, height)
    }

    @Test fun reducedMotionSnapsLiftButKeepsFadeAndDisposalReleasesMeasurement() {
        val agents = listOf(agent("child", "Worker"))
        var items by mutableStateOf(agents)
        var visible by mutableStateOf(true)
        var height = 0f
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                MaterialTheme {
                    if (visible) ConversationToolsRow("parent", emptyList(), items, {}, { height = it })
                }
            }
        }
        compose.runOnIdle { }
        assertTrue(height > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { items = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText("Worker").assertExists().assertIsNotEnabled()
        assertEquals(0f, height)
        compose.runOnIdle { items = agents }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Worker").assertExists()
        assertTrue(height > 0f)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { visible = false }
        compose.onNodeWithText("Worker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0f, height) }
    }

    @Test fun familyNavigationRetainsCapsulesWhileSelectionChanges() {
        var owner by mutableStateOf<String?>("parent")
        val family = SubagentFamilySnapshot("parent", listOf(agent("a", "Worker A"), agent("b", "Worker B")))
        var height = 0f
        var selected: String? = null
        compose.setContent {
            val current = family.forSelection(owner)
            MaterialTheme {
                ConversationToolsRow(owner, emptyList(), current?.agents.orEmpty(), { selected = it },
                    { height = it }, familyId = current?.familyId)
            }
        }
        val initialHeight = height
        val slot = "tools-slot:subagent:parent:a"
        val initialBounds = compose.onNodeWithTag(slot).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        listOf("a", "b", "parent").forEach { id ->
            compose.runOnIdle { owner = id }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            compose.onNodeWithText("Worker A").assertExists().performClick()
            assertEquals("a", selected)
            assertEquals(initialHeight, height, 0.1f)
            assertEquals(initialBounds, compose.onNodeWithTag(slot).fetchSemanticsNode().boundsInRoot)
            val selectedState = compose.onNodeWithText("Worker A").fetchSemanticsNode()
                .config[SemanticsProperties.Selected]
            assertEquals(id == "a", selectedState)
        }
        compose.runOnIdle { owner = "unrelated" }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Worker A").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Worker A").assertDoesNotExist()
        assertEquals(0f, height)
    }

    @Test fun statusCrossfadeKeepsFixedSlotAndProgressWithReducedMotion() {
        var status by mutableStateOf("idle")
        var expectedSize = 0f
        val progress = SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )
        lateinit var idle: String
        lateinit var completed: String
        compose.setContent {
            expectedSize = with(LocalDensity.current) { 18.dp.toPx() }
            idle = androidx.compose.ui.res.stringResource(R.string.subagent_idle)
            completed = androidx.compose.ui.res.stringResource(R.string.subagent_completed)
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                MaterialTheme {
                    ConversationSubagentCapsule(agent("child", "Worker", status), false, true, {})
                }
            }
        }
        val slot = compose.onNodeWithTag("subagent-status:child", useUnmergedTree = true)
        val initialBounds = slot.fetchSemanticsNode().boundsInRoot
        assertEquals(expectedSize, initialBounds.width, 0.1f)
        assertEquals(expectedSize, initialBounds.height, 0.1f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { status = "running" }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(idle, useUnmergedTree = true).assertExists()
        compose.onNode(progress, useUnmergedTree = true).assertExists()
        assertEquals(initialBounds, slot.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(idle, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { status = "completed" }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        compose.onNode(progress, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(completed, useUnmergedTree = true).assertExists()
        assertEquals(initialBounds, slot.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNode(progress, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(completed, useUnmergedTree = true).assertExists()
    }

    @Test fun capsulesNavigateScrollAndReleaseTheirMeasuredLift() {
        val agents = (0..8).map { index ->
            SubagentSummary("child-$index", "Worker $index", "Provider:model", "parent",
                listOf("running", "completed", "failed", "stopped", "idle")[index % 5])
        }
        var items by mutableStateOf(agents)
        var owner by mutableStateOf<String?>("parent")
        var selected: String? = null
        var height = 0f
        var expectedHeight = 0f
        compose.setContent {
            expectedHeight = with(LocalDensity.current) { 56.dp.toPx() }
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ConversationToolsRow(owner, emptyList(), items, { selected = it }, { height = it })
                }
            }
        }
        compose.onNodeWithText("Worker 0").assertExists().performClick()
        assertEquals("child-0", selected)
        assertEquals("Lift includes the capsule's 8dp bottom gap", expectedHeight, height, 0.1f)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        compose.onNodeWithText("Worker 8").performScrollTo()
        compose.onNodeWithText("Worker 8").assertExists().performClick()
        assertEquals("child-8", selected)
        compose.runOnIdle { items = emptyList() }
        compose.onNodeWithText("Worker 8").assertDoesNotExist()
        assertEquals(0f, height)
        compose.runOnIdle { items = agents; owner = null }
        compose.onNodeWithText("Worker 0").assertDoesNotExist()
        assertEquals(0f, height)
    }
}
