package com.newoether.agora.ui.chat.interaction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.newoether.agora.util.horizontalEdgeFade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HorizontalEdgeFadeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var drawingView: View

    @Test fun independentEdgeUpdatesChangeOnlyPaintAndKeepTheClickRegion() {
        var weights by mutableStateOf(0f to 1f)
        var clicks = 0
        compose.setContent {
            drawingView = LocalView.current
            Box(Modifier.size(200.dp, 60.dp).background(Color.White).testTag("host")) {
                Box(Modifier.size(200.dp, 60.dp).testTag("masked")
                    .horizontalEdgeFade(leftWeight = weights.first, rightWeight = weights.second)
                    .background(Color.Red).clickable { clicks++ })
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("masked").fetchSemanticsNode().boundsInRoot
        assertPixels { bitmap ->
            assertTrue("The reached left edge stays opaque", green(bitmap, 1) < 5)
            assertTrue("The overflowing right edge fades into the background", green(bitmap, 198) > 230)
            assertTrue("The interior stays opaque", green(bitmap, 100) < 5)
        }
        compose.runOnIdle { weights = 1f to 0f }
        compose.waitForIdle()
        assertPixels { bitmap ->
            assertTrue("Only the left edge now fades", green(bitmap, 1) > 230)
            assertTrue("The reached right edge stays opaque", green(bitmap, 198) < 5)
        }
        compose.runOnIdle { weights = 0f to 0f }
        compose.waitForIdle()
        assertPixels { bitmap ->
            assertTrue("A fully visible row has no left mask", green(bitmap, 1) < 5)
            assertTrue("A fully visible row has no right mask", green(bitmap, 198) < 5)
        }
        assertEquals(bounds, compose.onNodeWithTag("masked").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("masked").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun twoFadesOnANarrowViewportNeverEraseItsCenter() {
        compose.setContent {
            drawingView = LocalView.current
            Box(Modifier.size(40.dp, 60.dp).background(Color.White).testTag("host")) {
                Box(Modifier.size(40.dp, 60.dp).horizontalEdgeFade().background(Color.Red))
            }
        }
        compose.waitForIdle()
        assertPixels { bitmap ->
            assertTrue(green(bitmap, 0) > 230)
            assertTrue(green(bitmap, 39) > 230)
            assertTrue("Each fade is capped at half the viewport", green(bitmap, 20) < 20)
        }
    }

    private fun green(bitmap: Bitmap, x: Int): Int = android.graphics.Color.green(bitmap.getPixel(x, 30))

    private fun assertPixels(inspect: (Bitmap) -> Unit) {
        val bounds = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            compose.runOnUiThread {
                val canvas = Canvas(bitmap)
                canvas.translate(-bounds.left, -bounds.top)
                drawingView.draw(canvas)
            }
            inspect(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
