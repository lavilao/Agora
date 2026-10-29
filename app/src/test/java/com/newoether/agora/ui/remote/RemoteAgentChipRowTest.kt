package com.newoether.agora.ui.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.remote.RemoteDeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h250dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteAgentChipRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statusChangesRetainOneInformationalChipAndItsGeometry() {
        var status by mutableStateOf(RemoteDeviceStatus.CONNECTED)
        compose.setContent { MaterialTheme { RemoteAgentChipRow(status) } }
        fun assertNoVisibleStatus() {
            for (label in listOf("Online", "Offline", "Connecting")) {
                compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
            }
        }
        compose.onNodeWithContentDescription("Codex Desktop, Online").assertExists().assertHasNoClickAction()
        assertNoVisibleStatus()
        val initial = compose.onNodeWithText("Codex Desktop").getUnclippedBoundsInRoot()
        val initialChip = compose.onNodeWithTag("remote-agent-chip", useUnmergedTree = true).getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        for ((next, label) in listOf(
            RemoteDeviceStatus.ERROR to "Offline",
            RemoteDeviceStatus.CONNECTING to "Connecting",
            RemoteDeviceStatus.IDLE to "Offline",
        )) {
            compose.runOnIdle {
                status = next
                // With a paused clock, publish the write before requesting the recomposition frame.
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            repeat(4) {
                compose.mainClock.advanceTimeBy(50)
                compose.waitForIdle()
                assertNoVisibleStatus()
                assertEquals(initial, compose.onNodeWithText("Codex Desktop").getUnclippedBoundsInRoot())
                assertEquals(initialChip, compose.onNodeWithTag("remote-agent-chip", useUnmergedTree = true).getUnclippedBoundsInRoot())
            }
            compose.onNodeWithContentDescription("Codex Desktop, $label").assertExists().assertHasNoClickAction()
            assertNoVisibleStatus()
        }
        compose.onNodeWithText("Claude Code").assertDoesNotExist()
    }

    @Test fun errorRendersTheNeutralIdleDotWithReducedMotionAndNoAction() {
        var status by mutableStateOf(RemoteDeviceStatus.ERROR)
        lateinit var drawingView: View
        var expected = Color.Unspecified
        var expectedIdle = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    drawingView = LocalView.current
                    val statusColor = when (status) {
                        RemoteDeviceStatus.ERROR, RemoteDeviceStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        RemoteDeviceStatus.CONNECTING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        RemoteDeviceStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                    }
                    val container = MaterialTheme.colorScheme.secondaryContainer
                    SideEffect {
                        expected = statusColor
                        expectedIdle = statusColor.compositeOver(container)
                    }
                    Box(Modifier.size(400.dp, 80.dp).background(Color.Transparent)) {
                        RemoteAgentChipRow(status)
                    }
                }
            }
        }
        fun dotPixel(): Int {
            val bounds = compose.onNodeWithTag("remote-agent-status-dot", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                compose.runOnUiThread {
                    val canvas = Canvas(image)
                    canvas.translate(-bounds.left, -bounds.top)
                    drawingView.draw(canvas)
                }
                return image.getPixel(4, 4)
            } finally { image.recycle() }
        }
        compose.onNodeWithContentDescription("Codex Desktop, Offline").assertHasNoClickAction()
        val errorPixel = dotPixel()
        compose.runOnIdle { status = RemoteDeviceStatus.IDLE }
        compose.onNodeWithContentDescription("Codex Desktop, Offline").assertHasNoClickAction()
        val idlePixel = dotPixel()
        val expectedIdlePixel = expectedIdle.toArgb()
        // Canvas quantizes premultiplied alpha when compositing the translucent idle dot.
        for (shift in listOf(0, 8, 16, 24)) {
            val expectedChannel = (expectedIdlePixel ushr shift) and 0xff
            val actualChannel = (idlePixel ushr shift) and 0xff
            assertEquals("Idle dot channel $shift", expectedChannel.toDouble(), actualChannel.toDouble(), 1.0)
        }
        assertEquals("Remote dots never render red; error uses the idle gray", idlePixel, errorPixel)
        compose.runOnIdle { status = RemoteDeviceStatus.CONNECTED }
        compose.onNodeWithContentDescription("Codex Desktop, Online").assertHasNoClickAction()
        assertEquals(expected.toArgb(), dotPixel())
    }

    @Test fun capsuleGeometryAndPointerEventsReachPlatformRippleWithoutAddingAnAction() {
        lateinit var drawingView: View
        var containerColor = Color.Unspecified
        compose.setContent {
            drawingView = LocalView.current
            MaterialTheme {
                containerColor = MaterialTheme.colorScheme.secondaryContainer
                Box(Modifier.size(300.dp, 80.dp).background(Color.White)) {
                    RemoteAgentChipRow(RemoteDeviceStatus.CONNECTED)
                }
            }
        }
        val chip = compose.onNodeWithTag("remote-agent-chip", useUnmergedTree = true)
        val bounds = chip.fetchSemanticsNode().boundsInRoot
        assertEquals(40f, bounds.height, 0.01f)
        val surface = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            compose.runOnUiThread {
                val canvas = Canvas(surface)
                canvas.translate(-bounds.left, -bounds.top)
                drawingView.draw(canvas)
            }
            assertEquals("The visible surface has capsule corners", android.graphics.Color.WHITE, surface.getPixel(2, 2))
            assertEquals("The old outline position uses the selected container fill",
                containerColor.toArgb(), surface.getPixel(surface.width / 2, 0))
            assertTrue("The capsule contains a painted surface", surface.getPixel(surface.width / 2, surface.height / 2) != android.graphics.Color.WHITE)
        } finally {
            surface.recycle()
        }
        fun ripples(view: View): List<android.graphics.drawable.RippleDrawable> = buildList {
            (view.background as? android.graphics.drawable.RippleDrawable)?.let { add(it) }
            if (view is android.view.ViewGroup) {
                repeat(view.childCount) { addAll(ripples(view.getChildAt(it))) }
            }
        }
        // API 35 patterned ripples skip software Canvas drawing. Check the real platform
        // drawable's state instead; visible wave pixels require hardware rendering.
        chip.performTouchInput { down(center) }
        lateinit var ripple: android.graphics.drawable.RippleDrawable
        try {
            compose.runOnIdle {
                val pressed = ripples(drawingView.rootView).filter { android.R.attr.state_pressed in it.state }
                assertEquals("A real pointer press reaches one platform ripple", 1, pressed.size)
                ripple = pressed.single()
            }
            compose.onNodeWithContentDescription("Codex Desktop, Online").assertHasNoClickAction()
        } finally {
            chip.performTouchInput { up() }
        }
        compose.runOnIdle { assertTrue("Release clears the platform press", android.R.attr.state_pressed !in ripple.state) }
        chip.performTouchInput { down(center) }
        try {
            compose.runOnIdle { assertTrue("A new press reaches the same ripple", android.R.attr.state_pressed in ripple.state) }
        } finally {
            chip.performTouchInput { cancel() }
        }
        compose.runOnIdle { assertTrue("Cancellation clears the platform press", android.R.attr.state_pressed !in ripple.state) }
    }
}
