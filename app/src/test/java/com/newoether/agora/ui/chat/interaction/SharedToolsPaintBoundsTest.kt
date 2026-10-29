package com.newoether.agora.ui.chat.interaction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.SubagentSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class,
    qualifiers = "w1800dp-h500dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedToolsPaintBoundsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var drawingView: View

    private val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta, Color.Yellow)

    @Test fun sixLongNamedCapsulesPaintOnlyInsideTheirSlotsAtEverySampledFrame() {
        var visible by mutableStateOf(false)
        compose.setContent {
            drawingView = LocalView.current
            Box(Modifier.size(1600.dp, 120.dp).background(Color.White).testTag("paint-host")) {
                Row(Modifier.padding(start = 16.dp, top = 24.dp)) {
                    colors.forEachIndexed { index, color ->
                        MaterialTheme(colorScheme = lightColorScheme(
                            surfaceContainerHigh = color, onSurface = Color.White, onSurfaceVariant = Color.White,
                        )) {
                            var ready by remember { mutableStateOf(false) }
                            val painted = visible && ready
                            SharedToolSlot("paint-$index", index < colors.lastIndex, 8, {}, visible,
                                preserveContentWidth = true, contentVisible = painted,
                                onReadyChanged = { ready = it }) { modifier ->
                                ConversationSubagentCapsule(
                                    SubagentSummary("child-$index", "Worker $index with a deliberately long research name",
                                        "model", "parent", "idle"),
                                    selected = false, active = true, onSelect = {},
                                    modifier = modifier.width(240.dp).graphicsLayer { alpha = if (painted) 1f else 0f },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("Fully measured zero-width slots must paint nothing", 0, assertPaintBounds("zero"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = true }
        var enteringInk = 0
        var roundedFrames = 0
        var sawPartialWidth = false
        repeat(26) { frame ->
            advanceFrame()
            val ink = assertPaintBounds("enter-$frame")
            enteringInk += ink
            val width = compose.onNodeWithTag("tools-slot:paint-0").fetchSemanticsNode().boundsInRoot.width
            if (width > 0f && width < 248f) sawPartialWidth = true
            if (ink > 0) {
                assertEquals("A painted capsule has its full reserved width", 248f, width, 0f)
                assertRoundedRightEnd()
                roundedFrames++
            }
        }
        assertTrue("The raster test must inspect actual full rounded silhouettes", roundedFrames > 0)
        assertTrue("The raster test must exercise partially expanded slots", sawPartialWidth)
        assertTrue("Native raster capture must contain actual capsule paint", enteringInk > 1_000)
        compose.runOnIdle { visible = false }
        repeat(7) { frame -> advanceFrame(); assertPaintBounds("exit-$frame") }
        compose.runOnIdle { visible = true }
        repeat(23) { frame -> advanceFrame(); assertPaintBounds("interrupted-exit-$frame") }
        compose.runOnIdle { visible = false }
        repeat(23) { frame -> advanceFrame(); assertPaintBounds("final-exit-$frame") }
        assertEquals("A completed exit cannot retain any colored paint", 0, assertPaintBounds("exited"))
    }

    @Test
    @Config(qualifiers = "w412dp-h800dp-420dpi")
    fun phoneDensityWrapContentCapsulesKeepNaturalWidthsThroughSlotMotion() {
        var visible by mutableStateOf(false)
        val contentWidths = IntArray(2)
        val settledWidths = IntArray(2)
        compose.setContent {
            drawingView = LocalView.current
            MaterialTheme {
                Box(Modifier.size(390.dp, 120.dp).background(Color.White).testTag("wrap-host")) {
                    Row(Modifier.padding(start = 12.dp, top = 24.dp)) {
                        var todoReady by remember { mutableStateOf(false) }
                        SharedToolSlot("wrap-todo", true, 21, { settledWidths[0] = it }, visible,
                            preserveContentWidth = true, contentVisible = visible,
                            onReadyChanged = { todoReady = it }) { modifier ->
                            ConversationTodoBar(
                                "parent", listOf(com.newoether.agora.model.ConversationTodoItem("Task")), {},
                                modifier.then(Modifier.layout { measurable, constraints ->
                                    val child = measurable.measure(constraints)
                                    contentWidths[0] = child.width
                                    layout(child.width, child.height) { child.place(0, 0) }
                                }), inSharedRow = true, appearanceReady = todoReady,
                            )
                        }
                        var agentReady by remember { mutableStateOf(false) }
                        SharedToolSlot("wrap-agent", false, 0, { settledWidths[1] = it }, visible,
                            preserveContentWidth = true, onReadyChanged = { agentReady = it }) { modifier ->
                            ConversationSubagentCapsule(
                                SubagentSummary("child", "Local Chat Model Import", "model", "parent", "idle"),
                                selected = false, active = true, onSelect = {},
                                modifier = modifier.graphicsLayer { alpha = if (visible && agentReady) 1f else 0f }
                                    .then(Modifier.layout { measurable, constraints ->
                                        val child = measurable.measure(constraints)
                                        contentWidths[1] = child.width
                                        layout(child.width, child.height) { child.place(0, 0) }
                                    }),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val natural = contentWidths.toList()
        assertTrue("Measure actual wrap-content capsules at phone density", natural.all { it > 100 })
        compose.mainClock.autoAdvance = false
        var partialFrames = 0
        listOf(true, false, true).forEach { target ->
            compose.runOnIdle { visible = target }
            repeat(36) { frame ->
                advanceFrame()
                assertEquals("Frame $frame: animated slots never remeasure capsule contents", natural, contentWidths.toList())
                listOf("wrap-todo", "wrap-agent").forEachIndexed { index, key ->
                    val slot = compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().boundsInRoot
                    if (slot.width > 30f && slot.width < natural[index] - 2f) partialFrames++
                }
            }
        }
        assertTrue("The invariant must hold through intermediate entry and exit widths", partialFrames > 4)
    }

    @Test fun shrinkingAndZeroWidthSlotsRejectTouchesOutsideTheirOccupiedWidth() {
        var visible by mutableStateOf(true)
        val selections = mutableListOf<String>()
        compose.setContent {
            drawingView = LocalView.current
            MaterialTheme {
                Box(Modifier.size(400.dp, 120.dp).background(Color.White).testTag("hit-host")) {
                    Row(Modifier.padding(start = 16.dp, top = 24.dp)) {
                        SharedToolSlot("hit", false, 0, {}, visible) { modifier ->
                            ConversationSubagentCapsule(
                                SubagentSummary("child", "Worker with a long name", "model", "parent", "idle"),
                                selected = false, active = true, onSelect = { selections += it },
                                modifier = modifier.width(240.dp),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false }
        repeat(11) { advanceFrame() }
        val host = compose.onNodeWithTag("hit-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        val slot = compose.onNodeWithTag("tools-slot:hit").fetchSemanticsNode().boundsInRoot
        assertTrue("The hit test must run during collapse: $slot", slot.width > 0f && slot.width < 220f)
        host.performTouchInput { click(Offset(slot.right + 12f, slot.center.y) - origin) }
        assertTrue("Full-width child hit regions must not escape the clipped slot", selections.isEmpty())
        host.performTouchInput { click(slot.center - origin) }
        assertEquals("The occupied portion stays interactive", listOf("child"), selections)
        repeat(23) { advanceFrame() }
        host.performTouchInput { click(Offset(100f, 48f)) }
        assertEquals("Zero-width slots have no hit area", listOf("child"), selections)
    }

    @Test fun settledSlotKeepsOverflowSoRestingCapsuleShadowsAreNotClipped() {
        compose.setContent {
            drawingView = LocalView.current
            Box(Modifier.size(300.dp, 120.dp).background(Color.White).testTag("shadow-host")) {
                Row(Modifier.padding(start = 24.dp, top = 24.dp)) {
                    SharedToolSlot("shadow", false, 0, {}) { modifier ->
                        // A deterministic overflow probe also covers platforms whose test renderer omits shadows.
                        Box(modifier.width(120.dp).height(48.dp).drawBehind {
                            drawRect(Color.Red, Offset(-6f, -6f), Size(size.width + 12f, size.height + 12f))
                        })
                    }
                }
            }
        }
        compose.waitForIdle()
        val host = compose.onNodeWithTag("shadow-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        val bounds = compose.onNodeWithTag("tools-slot:shadow").fetchSemanticsNode().boundsInRoot
        val x = (bounds.left - origin.x - 3f).toInt()
        val y = (bounds.center.y - origin.y).toInt()
        withRaster("shadow-host") { bitmap ->
            assertEquals("Only moving slots clip; resting drawing retains its original extent",
                Color.Red, Color(bitmap.getPixel(x, y)))
        }
    }

    private fun assertRoundedRightEnd() {
        val origin = compose.onNodeWithTag("paint-host").fetchSemanticsNode().boundsInRoot.topLeft
        val slot = compose.onNodeWithTag("tools-slot:paint-0").fetchSemanticsNode().boundsInRoot
        val x = (slot.right - origin.x - 11f).toInt()
        val top = (slot.top - origin.y + 3f).toInt()
        val center = (slot.center.y - origin.y).toInt()
        withRaster("paint-host") { bitmap ->
            assertTrue("Animated pill retains its painted right center", Color(bitmap.getPixel(x, center)).green < 0.1f)
            assertTrue("Animated pill retains a curved right corner, not a vertical crop",
                Color(bitmap.getPixel(x, top)).green > 0.8f)
        }
    }

    private fun advanceFrame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun assertPaintBounds(stage: String): Int {
        val host = compose.onNodeWithTag("paint-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        val bounds = colors.indices.map { index ->
            compose.onNodeWithTag("tools-slot:paint-$index").fetchSemanticsNode().boundsInRoot
        }
        return withRaster("paint-host") { bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val counts = IntArray(colors.size)
            val left = IntArray(colors.size) { Int.MAX_VALUE }
            val right = IntArray(colors.size) { Int.MIN_VALUE }
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = Color(pixels[y * bitmap.width + x])
                val index = colors.indexOfFirst { color ->
                    abs(pixel.red - color.red) < 0.03f && abs(pixel.green - color.green) < 0.03f &&
                        abs(pixel.blue - color.blue) < 0.03f
                }
                if (index >= 0) {
                    counts[index]++
                    left[index] = minOf(left[index], x)
                    right[index] = maxOf(right[index], x)
                }
            }
            colors.indices.forEach { index ->
                val slot = bounds[index]
                if (slot.width == 0f) {
                    assertEquals("$stage capsule $index paints at zero occupied width", 0, counts[index])
                } else if (counts[index] > 0) {
                    assertTrue("$stage capsule $index paints left of its occupied slot: ${left[index]} vs $slot",
                        left[index] + origin.x >= slot.left - 1f)
                    assertTrue("$stage capsule $index paints into its neighbor: ${right[index]} vs $slot",
                        right[index] + origin.x < slot.right + 1f)
                }
            }
            counts.sum()
        }
    }

    private fun <T> withRaster(tag: String, inspect: (Bitmap) -> T): T {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(ceil(bounds.width).toInt(), ceil(bounds.height).toInt(),
            Bitmap.Config.ARGB_8888)
        try {
            // Draw the actual Compose host synchronously; SDK 35 WindowCapture/PixelCopy can stall.
            compose.runOnUiThread {
                val canvas = Canvas(bitmap)
                canvas.translate(-bounds.left, -bounds.top)
                drawingView.draw(canvas)
            }
            return inspect(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
