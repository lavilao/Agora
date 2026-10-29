package com.newoether.agora.ui.chat.interaction

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.SubagentCapsuleDisplayModes
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
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
class SubagentCapsuleMotionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: android.view.View
    private var density = 1f

    @Test fun roundedClipGrowsShrinksAndRetargetsWithoutRemeasuringText() = verifyClip(false)

    @Test fun rtlKeepsContentAndRoundedClipAtTheLogicalStart() = verifyClip(true)

    private fun verifyClip(rtl: Boolean) {
        val short = "1 Running"
        val long = "14 Subagents investigating a much longer worker title"
        var title by mutableStateOf(short)
        var clicks = 0
        compose.setContent {
            view = LocalView.current
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = lightColorScheme(surfaceContainerHigh = Color.Red)) {
                    Box(Modifier.size(360.dp, 100.dp).background(Color.White).testTag("host")) {
                        SubagentNavigationCapsule(title, "idle", false, true, "motion", { clicks++ },
                            Modifier.padding(16.dp).testTag("capsule"))
                    }
                }
            }
        }
        val initial = paintBounds()
        val shortLayout = textLayout(short).size
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { title = long }
        frames(1)
        assertEquals("First changed frame starts at the old edge", initial.width, paintBounds().width, 2f)
        val longLayout = textLayout(long).size
        val widths = mutableListOf<Float>()
        repeat(29) { frame ->
            frames(1)
            val bounds = paintBounds()
            widths += bounds.width
            assertEquals("Incoming text keeps its final constraints at frame $frame", longLayout, textLayout(long).size)
            if (frame < 10) assertEquals(shortLayout, textLayout(short).size)
            if (rtl) assertEquals(initial.right, bounds.right, 1f)
            else assertEquals(initial.left, bounds.left, 1f)
        }
        val expanded = paintBounds()
        assertTrue("Clip grows through multiple intermediate widths: $widths", widths.distinct().size > 8)
        assertTrue(expanded.width > initial.width + 30f * density)
        assertTrue(widths.zipWithNext().all { (a, b) -> b >= a - 1f })
        assertEquals(240f * density, expanded.width, 2f)
        assertRoundedEnd(expanded, rtl)
        compose.onNodeWithText(short, useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle { title = short }
        frames(8)
        val shrinking = paintBounds()
        assertTrue(shrinking.width > initial.width && shrinking.width < expanded.width)
        assertRoundedEnd(shrinking, rtl)
        assertEquals(longLayout, textLayout(long).size)
        assertEquals(shortLayout, textLayout(short).size)
        val beforeInterrupt = shrinking.width
        compose.runOnIdle { title = long }
        // The existing animation advances before this frame recomposes the new title.
        frames(1)
        val rebasedWidth = paintBounds().width
        assertTrue(rebasedWidth > initial.width && rebasedWidth <= beforeInterrupt)
        frames(1)
        assertEquals("A new title starts from the current clip", rebasedWidth, paintBounds().width, 2f)
        val returnWidths = mutableListOf<Float>()
        repeat(30) { frames(1); returnWidths += paintBounds().width }
        assertTrue("An interrupted return must animate rather than snap", returnWidths.distinct().size > 8)
        assertTrue(returnWidths.zipWithNext().all { (a, b) -> b >= a - 1f })
        assertEquals(expanded.width, paintBounds().width, 1f)
        compose.runOnIdle { title = short }
        val shrinkingWidths = mutableListOf<Float>()
        repeat(31) { frames(1); shrinkingWidths += paintBounds().width }
        assertTrue(shrinkingWidths.zipWithNext().all { (a, b) -> b <= a + 1f })
        assertEquals(initial.width, paintBounds().width, 1f)
        frames(10)
        assertEquals("Removing outgoing text cannot cause a late width correction", initial.width, paintBounds().width, 1f)
        val final = paintBounds()
        compose.onNodeWithTag("host").performTouchInput {
            click(Offset(if (rtl) final.left - 12f * density else final.right + 12f * density, final.center.y))
        }
        assertEquals("Hidden canvas cannot intercept taps", 0, clicks)
        compose.onNodeWithTag("host").performTouchInput { click(final.center) }
        assertEquals(1, clicks)
    }

    @Test fun titleContentActuallyFadesWhileBothPresentationsExist() = verifyFade(false)

    @Test fun reducedMotionSnapsTheClipButRetainsTheContentFade() = verifyFade(true)

    private fun verifyFade(reduced: Boolean) {
        val old = "MMMMMMMMMMMMMMMM"
        val next = "ii"
        var title by mutableStateOf(old)
        compose.setContent {
            view = LocalView.current
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme(colorScheme = lightColorScheme(surfaceContainerHigh = Color.Red,
                    onSurface = Color.Black, onSurfaceVariant = Color.Red)) {
                    Box(Modifier.size(360.dp, 100.dp).background(Color.White).testTag("host")) {
                        SubagentNavigationCapsule(title, "idle", false, true, "fade", {}, Modifier.padding(16.dp))
                    }
                }
            }
        }
        val initial = paintBounds()
        val oldBounds = compose.onNodeWithText(old, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val oldLayout = textLayout(old).size
        val probe = Rect(oldBounds.left + 14f * density, oldBounds.top,
            oldBounds.left + 36f * density, oldBounds.bottom)
        val fullInk = maximumInk(probe)
        assertTrue(fullInk > 0.9f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { title = next }
        frames(8)
        compose.onNodeWithText(old, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(next, useUnmergedTree = true).assertExists()
        assertEquals(oldLayout, textLayout(old).size)
        val fadedInk = maximumInk(probe)
        assertTrue("Outgoing glyphs must actually fade: $fadedInk vs $fullInk", fadedInk in 0.15f..0.85f)
        val intermediate = paintBounds()
        assertTrue(intermediate.width < initial.width)
        frames(24)
        val settled = paintBounds()
        if (reduced) assertEquals("Reduced Motion has no width tween", settled.width, intermediate.width, 1f)
        else assertTrue("Normal clip is still moving during the fade", intermediate.width > settled.width + 2f)
        compose.onNodeWithText(old, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(next, useUnmergedTree = true).assertExists()
        assertTrue("No outgoing glyph survives the fade", maximumInk(probe) < 0.02f)
    }

    @Test fun groupedRowOccupiedWidthFollowsTheVisibleClipOnEveryFrame() {
        var running by mutableStateOf(true)
        compose.setContent {
            view = LocalView.current
            density = LocalDensity.current.density
            MaterialTheme(colorScheme = lightColorScheme(surfaceContainerHigh = Color.Red)) {
                Box(Modifier.size(360.dp, 100.dp).background(Color.White)) {
                    ConversationToolsRow("parent", emptyList(), (1..14).map {
                        SubagentSummary("$it", "Worker $it", "model", "parent", if (running && it == 1) "running" else "idle")
                    }, {}, {}, subagentCapsuleDisplayMode = SubagentCapsuleDisplayModes.GROUPED)
                }
            }
        }
        val slot = compose.onNodeWithTag("tools-slot:subagents:parent")
        val initial = paintBounds().width
        compose.mainClock.autoAdvance = false
        for (next in listOf(false, true, false)) {
            compose.runOnIdle { running = next }
            val widths = mutableListOf<Float>()
            repeat(32) { frame ->
                frames(1)
                val painted = paintBounds()
                widths += painted.width
                assertEquals("No second slot tween at frame $frame", painted.width,
                    slot.fetchSemanticsNode().boundsInRoot.width, 2f)
            }
            assertTrue("Count update must have intermediate clip widths", widths.distinct().size > 4)
        }
        assertTrue(paintBounds().width > initial + 8f)
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun frames(count: Int) {
        repeat(count) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun paintBounds(): Rect = withRaster { bitmap ->
        var left = bitmap.width
        var right = -1
        var top = bitmap.height
        var bottom = -1
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = Color(pixels[y * bitmap.width + x])
            if (color.red > 0.98f && color.green < 0.02f && color.blue < 0.02f) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("Real capsule paint must exist", right > left && bottom > top)
        Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    private fun assertRoundedEnd(bounds: Rect, rtl: Boolean) = withRaster { bitmap ->
        val x = (if (rtl) bounds.left + 3f * density else bounds.right - 3f * density).toInt()
        assertTrue("Visible edge remains round", Color(bitmap.getPixel(x, (bounds.top + 2f * density).toInt())).green > 0.8f)
        assertTrue("Visible edge retains its center", Color(bitmap.getPixel(x, bounds.center.y.toInt())).green < 0.1f)
    }

    private fun maximumInk(bounds: Rect): Float = withRaster { bitmap ->
        var ink = 0f
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                ink = maxOf(ink, 1f - Color(bitmap.getPixel(x, y)).red)
            }
        }
        ink
    }

    private fun <T> withRaster(inspect: (Bitmap) -> T): T {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            return inspect(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
