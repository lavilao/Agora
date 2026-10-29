package com.newoether.agora.ui.chat.bottombar

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.AgoraTheme
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the production field and surface, including script fallback and real wrapping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerTypingGeometryTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var drawingView: View
    private var inputColor = Color.Black

    @Test
    fun defaultFontOnlyGrowsWhenTheVisualLineCountChanges() = assertTypingGeometry("app_default")

    @Test
    fun systemFontOnlyGrowsWhenTheVisualLineCountChanges() = assertTypingGeometry("system")

    @Test
    fun firstCjkFallbackOnTwoLatinLinesDoesNotGrowWithoutAnotherWrap() {
        val draft = TextFieldState()
        val barHeight = mutableFloatStateOf(0f)
        var measuredFieldHeight = 0f
        mount("system", draft, reduceMotion = true,
            inputModifier = Modifier.onSizeChanged { measuredFieldHeight = it.height.toFloat() },
            onBarHeightChanged = { barHeight.floatValue = it })
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("w".repeat(25))
        compose.waitForIdle()
        val before = sample(barHeight.floatValue)
        val measuredBefore = measuredFieldHeight
        assertEquals("Start with two Latin-only lines and room for one CJK character", 2, before.lines)
        field.performTextInput("文")
        compose.waitForIdle()
        val after = sample(barHeight.floatValue)
        println("COMPOSER_LATE_FALLBACK before=$before measuredBefore=$measuredBefore after=$after measuredAfter=$measuredFieldHeight")
        assertEquals("Adding fallback must not introduce another line in this fixture", 2, after.lines)
        assertEquals(before.fieldWidth, after.fieldWidth, 0.5f)
        assertEquals("Fallback on existing lines must not enlarge the measured field", measuredBefore, measuredFieldHeight, 0.5f)
        assertEquals("Fallback on existing lines must not enlarge the visible field", before.fieldHeight, after.fieldHeight, 0.5f)
        assertEquals("Fallback on existing lines must not enlarge the published bar", before.barHeight, after.barHeight, 0.5f)
    }

    @Test
    fun normalMotionDoesNotGrowBeforeWrappingAndPublishesTheVisibleHeight() {
        val draft = TextFieldState()
        val barHeight = mutableFloatStateOf(0f)
        val scroll = ScrollState(0)
        var measuredFieldHeight = 0f
        mount("app_default", draft, reduceMotion = false, scrollState = scroll,
            inputModifier = Modifier.onSizeChanged { measuredFieldHeight = it.height.toFloat() },
            onBarHeightChanged = { barHeight.floatValue = it })
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("agoraComposer文字输入" + "w".repeat(5))
        compose.waitForIdle()
        val before = sample(barHeight.floatValue)
        assertEquals(1, before.lines)
        compose.mainClock.autoAdvance = false
        try {
            field.performTextInput("w")
            repeat(28) {
                frame()
                val current = sample(barHeight.floatValue)
                assertEquals("A same-line edit must not resize during normal motion", 1, current.lines)
                assertEquals(before.fieldWidth, current.fieldWidth, 0.5f)
                assertEquals(before.fieldHeight, current.fieldHeight, 0.5f)
                assertEquals(before.barHeight, current.barHeight, 0.5f)
            }
            field.performTextInput("w")
            var previousHeight = before.barHeight
            var intermediateFrames = 0
            var sampledFirstGrowth = false
            var beforeGrowthPaint: PaintSample? = null
            var firstGrowthPaint: PaintSample? = null
            repeat(28) {
                frame()
                val current = sample(barHeight.floatValue)
                assertEquals("Text must already be laid out on two lines while height grows", 2, current.lines)
                assertEquals(before.fieldWidth, current.fieldWidth, 0.5f)
                assertTrue("Published height must not move backwards", current.barHeight + 0.5f >= previousHeight)
                assertTrue("The visible field stays within its measured content", current.fieldHeight <= measuredFieldHeight + 0.5f)
                if (current.fieldHeight > before.fieldHeight + 0.5f && current.fieldHeight < measuredFieldHeight - 0.5f) {
                    intermediateFrames++
                }
                if (it == 0) beforeGrowthPaint = paintSample("before-growth", scroll)
                if (!sampledFirstGrowth && current.fieldHeight > before.fieldHeight + 0.5f) {
                    firstGrowthPaint = paintSample("first-growth", scroll)
                    sampledFirstGrowth = true
                }
                println("COMPOSER_MOTION frame=$it sample=$current measuredField=$measuredFieldHeight scroll=${scroll.value}")
                previousHeight = current.barHeight
            }
            val after = sample(barHeight.floatValue)
            val settledPaint = paintSample("settled", scroll)
            println("COMPOSER_PAINT beforeGrowth=$beforeGrowthPaint firstGrowth=$firstGrowthPaint settled=$settledPaint")
            assertTrue("Exercise the production size animation and its clipping", intermediateFrames > 0)
            assertTrue("Wrapping must increase visible height", after.fieldHeight > before.fieldHeight)
            assertEquals("The field is fully visible after the 400ms animation", measuredFieldHeight, after.fieldHeight, 0.5f)
            assertEquals("Only the field contributes the animated height delta",
                after.fieldHeight - before.fieldHeight, after.barHeight - before.barHeight, 0.5f)
            val growthPaint = requireNotNull(firstGrowthPaint)
            assertTrue("Raster capture must contain the production first line: $growthPaint", growthPaint.firstLineInk > 0)
            assertTrue("A growing field must already paint second-line glyphs: $growthPaint", growthPaint.secondLineInk > 0)
            assertTrue("The settled field must paint its second line: $settledPaint", settledPaint.secondLineInk > 0)
            assertEquals("Two lines must not be hidden by internal scrolling at first growth", 0, growthPaint.scroll)
            assertEquals("Two lines must not be hidden by internal scrolling after settling", 0, settledPaint.scroll)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun assertTypingGeometry(fontPreference: String) {
        val draft = TextFieldState()
        val barHeight = mutableFloatStateOf(0f)
        mount(fontPreference, draft, reduceMotion = true, onBarHeightChanged = { barHeight.floatValue = it })
        compose.onNode(hasSetTextAction()).performClick()
        var previous: Sample? = null
        var wrapped = false
        val typed = StringBuilder()
        val sequence = "agoraComposer" + "文字输入" + "w".repeat(40)
        for (character in sequence) {
            typed.append(character)
            compose.onNode(hasSetTextAction()).performTextInput(character.toString())
            compose.waitForIdle()
            val current = sample(barHeight.floatValue)
            val old = previous
            println("COMPOSER_TYPING font=$fontPreference text=$typed sample=$current")
            if (old != null) {
                assertEquals("Typing must not change the field width", old.fieldWidth, current.fieldWidth, 0.5f)
                if (old.lines == current.lines) {
                    // Script fallback may change paragraph metrics inside the same visible height.
                    assertEquals("No new visual line at '$typed': $old -> $current", old.fieldHeight, current.fieldHeight, 0.5f)
                    assertEquals("No new visual line must keep the published bar height", old.barHeight, current.barHeight, 0.5f)
                } else if (current.lines > old.lines) {
                    wrapped = true
                    assertTrue("A wrapped line must increase field height", current.fieldHeight > old.fieldHeight)
                }
            }
            previous = current
        }
        assertTrue("Sequential input must cross at least one actual wrap", wrapped)
    }

    private fun textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        return layouts.single()
    }

    private fun sample(barHeight: Float): Sample {
        val layout = textLayout()
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot
        assertEquals("Published bar height excludes only the separate 12dp lift", surface.height - 12f, barHeight, 0.5f)
        return Sample(layout.lineCount, field.width, field.height, layout.size.height, barHeight)
    }

    private fun paintSample(stage: String, scroll: ScrollState): PaintSample {
        val layout = textLayout()
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val textLeft = field.left + with(compose.density) {
            (COMPOSER_TEXT_START_INSET - COMPOSER_HOST_SIDE_PADDING).toPx()
        }
        val textTop = field.top + with(compose.density) {
            (COMPOSER_TEXT_TOP_INSET - COMPOSER_HOST_TOP_PADDING).toPx()
        } - scroll.value
        val bitmap = Bitmap.createBitmap(ceil(viewport.width).toInt(), ceil(viewport.height).toInt(), Bitmap.Config.ARGB_8888)
        try {
            // Use the production Compose host draw; SDK 35 WindowCapture/PixelCopy can stall.
            compose.runOnUiThread {
                val canvas = Canvas(bitmap)
                canvas.translate(-viewport.left, -viewport.top)
                drawingView.draw(canvas)
            }
            fun lineInk(line: Int): Int {
                val left = ceil(textLeft + layout.getLineLeft(line) - viewport.left).toInt().coerceIn(0, bitmap.width)
                // Exclude the caret at the line end; only glyph-colored pixels count.
                val right = (textLeft + layout.getLineRight(line) - viewport.left - 2f).toInt().coerceIn(left, bitmap.width)
                val top = ceil(maxOf(field.top, textTop + layout.getLineTop(line)) - viewport.top).toInt().coerceIn(0, bitmap.height)
                val bottom = ceil(minOf(field.bottom, textTop + layout.getLineBottom(line)) - viewport.top).toInt().coerceIn(top, bitmap.height)
                var ink = 0
                for (y in top until bottom) for (x in left until right) {
                    val pixel = Color(bitmap.getPixel(x, y))
                    if (pixel.alpha > 0.8f && abs(pixel.red - inputColor.red) < 0.12f &&
                        abs(pixel.green - inputColor.green) < 0.12f && abs(pixel.blue - inputColor.blue) < 0.12f) ink++
                }
                return ink
            }
            return PaintSample(stage, lineInk(0), lineInk(1), field.height,
                textTop + layout.getLineTop(1) - field.top, textTop + layout.getLineBottom(1) - field.top, scroll.value)
        } finally {
            bitmap.recycle()
        }
    }

    private fun frame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun mount(
        fontPreference: String,
        draft: TextFieldState,
        reduceMotion: Boolean,
        inputModifier: Modifier = Modifier,
        scrollState: ScrollState = ScrollState(0),
        onBarHeightChanged: (Float) -> Unit,
    ) {
        val focus = FocusRequester()
        compose.setContent {
            drawingView = LocalView.current
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = reduceMotion)) {
                AgoraTheme(fontPreference = fontPreference) {
                    inputColor = MaterialTheme.colorScheme.onSurface
                    Box(Modifier.width(328.dp).height(600.dp).testTag("viewport")) {
                        ChatComposerSurface(
                            isExpanded = false,
                            onBarHeightChanged = onBarHeightChanged,
                            modifier = Modifier.align(Alignment.BottomCenter).testTag("surface"),
                        ) {
                            ChatComposerLayout(
                                textFieldState = draft,
                                focusRequester = focus,
                                onInputFocusChanged = {},
                                isExpanded = false,
                                isExpandAnimating = false,
                                onExpand = {},
                                onCollapse = {},
                                inputModifier = inputModifier,
                                scrollState = scrollState,
                                controls = { Spacer(Modifier.height(COMPOSER_CONTROL_HEIGHT)) },
                            )
                        }
                    }
                }
            }
        }
    }

    private data class Sample(
        val lines: Int,
        val fieldWidth: Float,
        val fieldHeight: Float,
        val paragraphHeight: Int,
        val barHeight: Float,
    )

    private data class PaintSample(
        val stage: String,
        val firstLineInk: Int,
        val secondLineInk: Int,
        val visibleFieldHeight: Float,
        val secondLineTop: Float,
        val secondLineBottom: Float,
        val scroll: Int,
    )
}
