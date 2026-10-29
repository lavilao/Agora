package com.newoether.agora.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h600dp-mdpi")
@OptIn(ExperimentalComposeUiApi::class)
class SettingsContextOptionsTest {
    @get:Rule val compose = createComposeRule()
    private var previousInspectorState = false

    @Before fun enableModifierInspection() {
        previousInspectorState = isDebugInspectorInfoEnabled
        isDebugInspectorInfoEnabled = true
    }

    @After fun restoreModifierInspection() {
        isDebugInspectorInfoEnabled = previousInspectorState
    }

    @Test fun currentModelOptionKeepsRoundedClicksAndSingleCallbacks() {
        exerciseOption(provider = null)
    }

    @Test fun providerModelOptionKeepsRoundedClicksAndSingleCallbacks() {
        exerciseOption(provider = "Provider")
    }

    private fun exerciseOption(provider: String?) {
        var selected by mutableStateOf(false)
        var callbacks = 0
        compose.setContent {
            MaterialTheme {
                AlertDialog(
                    onDismissRequest = {},
                    text = {
                        CompactModelItem(
                            label = "Compact model",
                            provider = provider,
                            selected = selected,
                            onClick = {
                                callbacks++
                                selected = !selected
                            },
                        )
                    },
                    confirmButton = {},
                )
            }
        }
        val row = compose.onNode(hasClickAction() and hasText("Compact model"))
        row.assertHasClickAction()
        if (provider != null) compose.onNodeWithText(provider).assertExists()
        val node = row.fetchSemanticsNode()
        val clippedLayer = node.layoutInfo.getModifierInfo()
            .mapNotNull { it.modifier as? InspectableValue }
            .firstOrNull { value ->
                value.inspectableElements.any { it.name == "clip" && it.value == true }
            }
        assertNotNull("The actual Context row clips its clickable layer", clippedLayer)
        val shape = clippedLayer!!.inspectableElements
            .first { it.name == "shape" }.value as Shape
        val outline = shape.createOutline(
            Size(node.boundsInRoot.width, node.boundsInRoot.height),
            LayoutDirection.Ltr,
            Density(1f),
        ) as Outline.Rounded
        assertEquals("The actual row uses the 24dp option corner", 24f, outline.roundRect.topLeftCornerRadius.x, 0.01f)
        val initialBounds = node.boundsInRoot
        val radio = compose.onNode(isSelectable(), useUnmergedTree = true)
        radio.assertIsNotSelected()
        row.performTouchInput { click(Offset(width - 8f, height / 2f)) }
        compose.runOnIdle { assertEquals(1, callbacks) }
        radio.assertIsSelected()
        assertEquals(initialBounds, row.fetchSemanticsNode().boundsInRoot)
        radio.performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, callbacks) }
        radio.assertIsNotSelected()
        row.performTouchInput {
            down(Offset(width - 8f, height / 2f))
            cancel()
        }
        compose.runOnIdle { assertEquals("A cancelled row press does not select", 2, callbacks) }
    }
}
