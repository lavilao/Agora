package com.newoether.agora.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgoraOptionHighlightDrawingTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var drawingView: View

    @Test
    fun pressedDialogOptionClipsItsIndicationAndCommitsOnlyOnRelease() {
        assertPressedHighlight(sheet = false)
    }

    @Test
    fun pressedSheetOptionKeepsItsInsetAndRoundedIndication() {
        assertPressedHighlight(sheet = true)
    }

    private fun assertPressedHighlight(sheet: Boolean) {
        var clicks = 0
        compose.setContent {
            drawingView = LocalView.current
            // A full rectangular press indication makes a missing outer clip observable,
            // independent of platform ripple animation timing and opacity.
            CompositionLocalProvider(LocalIndication provides PressFillIndication) {
                Box(Modifier.size(240.dp, 72.dp).background(Color.White).testTag("host")) {
                    val click = if (sheet) Modifier.sheetOptionClickable { clicks++ }
                        else Modifier.optionClickable { clicks++ }
                    Box(Modifier.fillMaxSize().then(click).testTag("option"))
                }
            }
        }
        compose.waitForIdle()
        assertPixel(120, 36, Color.White)
        val option = compose.onNodeWithTag("option")
        option.performTouchInput { down(center) }
        compose.waitForIdle()
        try {
            assertEquals("Press must not invoke the callback", 0, clicks)
            assertPixel(120, 36, Color.Red)
            val inset = if (sheet) 8 else 0
            assertPixel(inset + 1, 1, Color.White)
            assertPixel(238 - inset, 1, Color.White)
            assertPixel(inset + 1, 70, Color.White)
            assertPixel(238 - inset, 70, Color.White)
            assertPixel(inset + 1, 36, Color.Red)
            if (sheet) {
                assertPixel(3, 36, Color.White)
                assertPixel(236, 36, Color.White)
            }
        } finally {
            option.performTouchInput { up() }
        }
        compose.waitForIdle()
        assertEquals("Release keeps a single click owner", 1, clicks)
        assertPixel(120, 36, Color.White)
    }

    private fun assertPixel(x: Int, y: Int, expected: Color) {
        val bounds = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            compose.runOnUiThread {
                val canvas = Canvas(bitmap)
                canvas.translate(-bounds.left, -bounds.top)
                drawingView.draw(canvas)
            }
            assertEquals("Paint at ($x, $y)", expected, Color(bitmap.getPixel(x, y)))
        } finally {
            bitmap.recycle()
        }
    }

    private data object PressFillIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): Modifier.Node =
            PressFillNode(interactionSource)
    }

    private class PressFillNode(private val interactionSource: InteractionSource) :
        Modifier.Node(), DrawModifierNode {
        private var pressed = false

        override fun onAttach() {
            pressed = false
            coroutineScope.launch {
                val presses = mutableSetOf<PressInteraction.Press>()
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> presses.add(interaction)
                        is PressInteraction.Release -> presses.remove(interaction.press)
                        is PressInteraction.Cancel -> presses.remove(interaction.press)
                    }
                    pressed = presses.isNotEmpty()
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed) drawRect(Color.Red)
        }
    }
}
