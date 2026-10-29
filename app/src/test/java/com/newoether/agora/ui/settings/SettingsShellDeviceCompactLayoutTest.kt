package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h1000dp-mdpi")
class SettingsShellDeviceCompactLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val viewModel = mockk<ChatViewModel>()
    private val device = ShellDeviceConfig(
        id = "ssh", name = "Build Host", description = "Build machine", type = "ssh",
        sshHost = "build.example.test", sshPort = 2222, sshUser = "builder",
    )
    private lateinit var methodLabel: String

    private fun showEditor(width: Dp = 400.dp, height: Dp = 900.dp, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current.density
            val context = LocalContext.current
            methodLabel = context.getString(R.string.shell_ssh_auth_method)
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
            ) {
                MaterialTheme {
                    Box(Modifier.size(width, height)) {
                        SettingsShellDeviceEditor(
                            initial = device, isNew = false, viewModel = viewModel,
                            existingDevices = listOf(device), showDocFab = false,
                            isCurrent = { true }, onBack = {},
                        )
                    }
                }
            }
        }
    }

    private fun container(name: String) = compose.onNodeWithTag("shell-field-$name", useUnmergedTree = true)
    private fun field(name: String) = compose.onNode(
        hasSetTextAction() and hasAnyAncestor(hasTestTag("shell-field-$name")),
    )
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun fourHundredDpEditorPlacesHostAndNinetySixDpPortOnOneLine() {
        showEditor()
        field("port").performScrollTo()
        val host = container("host").getUnclippedBoundsInRoot()
        val port = container("port").getUnclippedBoundsInRoot()
        val endpoint = compose.onNodeWithTag("shell-ssh-endpoint", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals(host.top, port.top)
        assertEquals(96.dp, port.right - port.left)
        assertEquals(12.dp, port.left - host.right)
        assertEquals(endpoint.left, host.left)
        assertEquals(endpoint.right, port.right)
        assertTrue("Host receives the remaining width", host.right - host.left > port.right - port.left)
        assertEquals(14.sp, layout(field("host")).layoutInput.style.fontSize)
        assertEquals(14.sp, layout(field("port")).layoutInput.style.fontSize)
    }

    @Test
    fun narrowEditorStacksIndependentHostAndPortFieldsAtFullWidth() {
        showEditor(width = 280.dp)
        assertStackedEndpoint()
    }

    @Test
    fun enlargedFontsStackEndpointFieldsWithoutShrinkingTheirText() {
        showEditor(fontScale = 1.6f)
        assertStackedEndpoint()
        assertEquals(14.sp, layout(field("host")).layoutInput.style.fontSize)
        assertEquals(14.sp, layout(field("port")).layoutInput.style.fontSize)
        val portLabel = compose.onNode(
            hasText("Port") and hasAnyAncestor(hasTestTag("shell-field-port")),
            useUnmergedTree = true,
        )
        val style = layout(portLabel).layoutInput.style
        assertEquals(16.sp, style.fontSize)
        assertEquals(FontWeight.Medium, style.fontWeight)
    }

    private fun assertStackedEndpoint() {
        field("port").performScrollTo().assertIsDisplayed()
        val host = container("host").getUnclippedBoundsInRoot()
        val port = container("port").getUnclippedBoundsInRoot()
        assertEquals(host.left, port.left)
        assertEquals(host.right, port.right)
        assertEquals(12.dp, port.top - host.bottom)
        field("host").assertTextEquals(device.sshHost)
        field("port").assertTextEquals(device.sshPort.toString())
    }

    @Test
    fun identityFieldsShareOneItemAndUsernamePrecedesAuthenticationMethodInOneItem() {
        showEditor()
        field("description").performScrollTo()
        val identity = hasAnyAncestor(hasTestTag("shell-device-identity"))
        container("name").assert(identity)
        container("description").assert(identity)
        val name = container("name").getUnclippedBoundsInRoot()
        val description = container("description").getUnclippedBoundsInRoot()
        assertEquals(name.left, description.left)
        assertEquals(name.right, description.right)
        assertEquals(12.dp, description.top - name.bottom)
        field("user").performScrollTo()
        container("user").assert(hasAnyAncestor(hasTestTag("shell-ssh-authentication")))
        val method = compose.onNode(
            hasText(methodLabel) and hasAnyAncestor(hasTestTag("shell-ssh-authentication")),
            useUnmergedTree = true,
        )
        val username = container("user").getUnclippedBoundsInRoot()
        assertEquals(12.dp, method.getUnclippedBoundsInRoot().top - username.bottom)
        compose.onNodeWithTag("shell-auth-method", useUnmergedTree = true)
            .assert(hasAnyAncestor(hasTestTag("shell-ssh-authentication")))
        field("user").assertTextEquals(device.sshUser)
    }

    @Test
    fun shortEnlargedFontEditorCanScrollToEveryEndpointAndAuthenticationField() {
        showEditor(height = 400.dp, fontScale = 1.6f)
        for (name in listOf("name", "description", "host", "port", "user")) {
            field(name).performScrollTo().assertIsDisplayed()
        }
        compose.onNode(
            hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("shell-auth-method", useUnmergedTree = true).assertExists()
        field("host").performScrollTo().assertIsDisplayed()
        field("host").assertTextEquals(device.sshHost)
    }
}
