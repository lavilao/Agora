package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class SettingsSubagentTranscriptionParityTest {
    @get:Rule val compose = createComposeRule()

    private val alpha = "OpenAI:alpha"
    private val beta = "OpenAI:beta"
    private val local = "Local:gamma"
    private val custom = "Custom:delta"
    private val aliases = mapOf(alpha to "Alpha", beta to "Beta", local to "Gamma", custom to "Delta")
    private val global = linkedSetOf(alpha, beta, local, custom)
    private val list = MutableStateFlow<Set<String>>(setOf(alpha))
    private val recommendation = MutableStateFlow<String?>(null)
    private val additions = mutableListOf<Set<String>>()
    private val removals = mutableListOf<String>()
    private val recommendations = mutableListOf<String?>()
    private var configured by mutableStateOf<Set<String>?>(setOf(alpha))
    private var selected by mutableStateOf<String?>(null)
    private var reference by mutableStateOf(true)
    private lateinit var strings: Map<Int, String>

    private fun show(models: Set<String>? = setOf(alpha), model: String? = null) {
        configured = models
        selected = model
        list.value = models.orEmpty()
        recommendation.value = model
        val settings = mockk<SettingsRepository>(relaxed = true)
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        every { viewModel.settings } returns settings
        every { settings.imageTranscriptionEnabled } returns MutableStateFlow(true)
        every { settings.imageTranscriptionEnabledModels } returns list
        every { settings.imageTranscriptionModel } returns recommendation
        every { settings.imageTranscriptionBatchSize } returns MutableStateFlow(3)
        every { settings.imageTranscriptionPrompt } returns MutableStateFlow("")
        every { settings.modelAliases } returns MutableStateFlow(aliases)
        every { settings.enabledModels } returns MutableStateFlow(global)
        every { settings.customProviders } returns MutableStateFlow(emptyList<CustomProviderConfig>())
        every { settings.showDocumentationFab } returns MutableStateFlow(false)
        every { settings.setImageTranscriptionModel(any()) } answers {
            val value = firstArg<String?>()
            recommendations += value
            recommendation.value = value
            Job().apply { complete() }
        }
        every { settings.addImageTranscriptionModels(any()) } answers {
            val value = firstArg<Set<String>>()
            additions += value
            list.value += value
            Job().apply { complete() }
        }
        every { settings.removeImageTranscriptionModel(any()) } answers {
            val value = firstArg<String>()
            removals += value
            list.value -= value
            Job().apply { complete() }
        }
        compose.setContent {
            val context = LocalContext.current
            strings = listOf(
                R.string.transcription_no_model, R.string.transcription_add_model,
                R.string.subagents_no_recommended_model, R.string.subagents_add_models,
                R.string.provider_add, R.string.provider_cancel, R.string.delete, R.string.options,
            ).associateWith { context.getString(it) }
            CompositionLocalProvider(
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
                LocalSettingsScrollRetention provides null,
            ) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        if (reference) {
                            SettingsTranscriptionPage(viewModel, {})
                        } else {
                            SettingsSubagentContent(
                                subagentToolsEnabled = true,
                                subagentEnabledModels = configured,
                                subagentRecommendedModel = selected,
                                enabledModels = global,
                                modelAliases = aliases,
                                customProviders = emptyList(),
                                onSubagentToolsEnabledChange = {},
                                onAddModels = {
                                    additions += it
                                    configured = configured.orEmpty() + it
                                },
                                onRemoveModel = {
                                    removals += it
                                    configured = configured.orEmpty() - it
                                },
                                onRecommendedModelChange = {
                                    recommendations += it
                                    selected = it
                                },
                                onBack = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun text(id: Int) = strings.getValue(id)

    private fun row(label: String, dialog: Boolean = false) = compose.onNode(
        hasClickAction() and hasText(label) and
            (if (dialog) hasAnyAncestor(isDialog()) else !hasAnyAncestor(isDialog())),
    )

    private fun recommendationRow() = (if (reference) {
        compose.onNode(
            hasClickAction() and
                hasText(recommendation.value?.let(aliases::getValue) ?: text(R.string.transcription_no_model)) and
                !hasAnyAncestor(isDialog()) and
                !hasAnyDescendant(hasContentDescription(text(R.string.options))),
        )
    } else compose.onNodeWithTag("subagent-recommended-model")).performScrollTo()

    private fun modelRow(model: String) = if (reference) {
        compose.onNode(
            hasClickAction() and hasText(aliases.getValue(model)) and
                hasAnyDescendant(hasContentDescription(text(R.string.options))),
        )
    } else compose.onNodeWithTag("subagent-model:$model")

    private fun addRow() = row(text(
        if (reference) R.string.transcription_add_model else R.string.subagents_add_models,
    ))

    private fun control(label: String, role: Role) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, role) and
            hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(label))) and
            hasAnyAncestor(isDialog()),
        useUnmergedTree = true,
    )

    private fun dialogRow(label: String) = row(label, dialog = true)

    private fun useSubagents() = compose.runOnIdle { reference = false }

    private fun cancel() {
        compose.onNodeWithText(text(R.string.provider_cancel)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    private fun geometry(
        node: SemanticsNodeInteraction,
        includeChildren: Boolean = true,
    ): List<Rect> {
        val id = node.fetchSemanticsNode().id
        val root = compose.onNode(
            SemanticsMatcher("Same production node") { it.id == id },
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val origin = root.boundsInRoot.topLeft
        fun bounds(current: SemanticsNode): List<Rect> = listOf(
            current.boundsInRoot.translate(Offset(-origin.x, -origin.y)),
        ) + if (includeChildren) current.children.flatMap(::bounds) else emptyList()
        return bounds(root)
    }

    private fun assertSameGeometry(
        expected: List<Rect>,
        actual: SemanticsNodeInteraction,
        includeChildren: Boolean = true,
    ) {
        val observed = geometry(actual, includeChildren)
        assertEquals("Semantic layout node count", expected.size, observed.size)
        expected.zip(observed).forEachIndexed { index, (before, after) ->
            assertEquals("Node $index left", before.left, after.left, 0.01f)
            assertEquals("Node $index top", before.top, after.top, 0.01f)
            assertEquals("Node $index width", before.width, after.width, 0.01f)
            assertEquals("Node $index height", before.height, after.height, 0.01f)
        }
    }

    @Test
    fun emptyRecommendationUsesTheActualTranscription64dpRow() {
        show()
        val baseline = geometry(recommendationRow(), includeChildren = false)
        assertEquals(64f, baseline.first().height, 0.01f)
        useSubagents()
        assertSameGeometry(baseline, recommendationRow(), includeChildren = false)
        recommendationRow().performClick()
        dialogRow(text(R.string.subagents_no_recommended_model)).assertExists()
    }

    @Test
    fun defaultNullListMatchesTranscriptionEmptyRowsAndAddsOnlyTheFirstSelection() {
        show(models = null)
        val recommendationBaseline = geometry(recommendationRow(), includeChildren = false)
        val addBaseline = geometry(addRow().performScrollTo(), includeChildren = false)
        addRow().performClick()
        control("Beta", Role.Checkbox).performClick()
        compose.onNodeWithText(text(R.string.provider_add)).performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(beta)), additions) }

        useSubagents()
        recommendationRow().assertIsNotEnabled()
        assertSameGeometry(recommendationBaseline, recommendationRow(), includeChildren = false)
        assertSameGeometry(addBaseline, addRow().performScrollTo(), includeChildren = false)
        global.forEach { modelRow(it).assertDoesNotExist() }
        compose.runOnIdle { assertEquals(null, configured) }
        addRow().performClick()
        control("Beta", Role.Checkbox).performClick()
        compose.onNodeWithText(text(R.string.provider_add)).performClick()
        modelRow(beta).assertExists()
        (global - beta).forEach { modelRow(it).assertDoesNotExist() }
        compose.runOnIdle {
            assertEquals(setOf(beta), configured)
            assertEquals(listOf(setOf(beta), setOf(beta)), additions)
            assertTrue(recommendations.isEmpty())
        }
    }

    @Test
    fun selectedRecommendationMatchesTranscriptionModelAndProviderLayout() {
        show(model = alpha)
        val baseline = geometry(recommendationRow())
        useSubagents()
        assertSameGeometry(baseline, recommendationRow())
    }

    @Test
    fun localRecommendationMatchesTranscriptionIconAndRowLayout() {
        show(models = setOf(local), model = local)
        val baseline = geometry(recommendationRow())
        useSubagents()
        assertSameGeometry(baseline, recommendationRow())
    }

    @Test
    fun providerAndLocalModelRowsUseActualTranscriptionGeometry() {
        show(models = global)
        val baseline = global.associateWith { geometry(modelRow(it).performScrollTo()) }
        useSubagents()
        global.forEach { assertSameGeometry(baseline.getValue(it), modelRow(it).performScrollTo()) }
    }

    @Test
    fun addActionMatchesTranscriptionAndEmptyConfirmationRemainsEnabled() {
        show(models = emptySet())
        val baseline = geometry(addRow().performScrollTo(), includeChildren = false)
        assertEquals(56f, baseline.first().height, 0.01f)
        addRow().performClick()
        compose.onNodeWithText(text(R.string.provider_add)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet<String>()), additions) }
        useSubagents()
        assertSameGeometry(baseline, addRow().performScrollTo(), includeChildren = false)
        addRow().performClick()
        compose.onNodeWithText(text(R.string.provider_add)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet<String>(), emptySet()), additions) }
    }

    @Test
    fun recommendationDialogMatchesActualTranscriptionRowsAndRadioTargets() {
        show(models = setOf(alpha, beta), model = alpha)
        recommendationRow().performClick()
        val selectedBaseline = geometry(dialogRow("Alpha"))
        val unselectedBaseline = geometry(dialogRow("Beta"))
        control("Beta", Role.RadioButton).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(beta), recommendations) }
        useSubagents()
        recommendationRow().performClick()
        assertSameGeometry(selectedBaseline, dialogRow("Alpha"))
        assertSameGeometry(unselectedBaseline, dialogRow("Beta"))
        control("Beta", Role.RadioButton).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(beta, beta), recommendations) }
        recommendationRow().performClick()
        control(text(R.string.subagents_no_recommended_model), Role.RadioButton).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(beta, beta, null), recommendations) }
    }

    @Test
    fun addDialogMatchesTranscriptionCheckboxAndLabelTargets() {
        show()
        addRow().performScrollTo().performClick()
        val rowBaseline = geometry(dialogRow("Beta"))
        val controlBaseline = geometry(control("Beta", Role.Checkbox))
        control("Beta", Role.Checkbox).performClick()
        dialogRow("Gamma").performClick()
        compose.runOnIdle { assertTrue(additions.isEmpty()) }
        compose.onNodeWithText(text(R.string.provider_add)).performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(beta, local)), additions) }
        useSubagents()
        addRow().performScrollTo().performClick()
        assertSameGeometry(rowBaseline, dialogRow("Beta"))
        assertSameGeometry(controlBaseline, control("Beta", Role.Checkbox))
        control("Beta", Role.Checkbox).performClick()
        dialogRow("Gamma").performClick()
        compose.runOnIdle { assertEquals(1, additions.size) }
        compose.onNodeWithText(text(R.string.provider_add)).performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(beta, local), setOf(beta, local)), additions) }
    }

    @Test
    fun cancelledAddSelectionIsDiscardedLikeTranscription() {
        show()
        addRow().performScrollTo().performClick()
        control("Beta", Role.Checkbox).performClick()
        cancel()
        addRow().performClick()
        compose.onNodeWithText(text(R.string.provider_add)).performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet<String>()), additions) }
        useSubagents()
        addRow().performScrollTo().performClick()
        control("Beta", Role.Checkbox).performClick()
        cancel()
        addRow().performClick()
        compose.onNodeWithText(text(R.string.provider_add)).performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet<String>(), emptySet()), additions) }
    }

    @Test
    fun modelOverflowAndDeleteMenuMatchTranscriptionWithoutEarlyMutation() {
        show()
        fun options() = compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription, listOf(text(R.string.options)),
            ) and hasClickAction(),
        )
        val optionsBaseline = geometry(options().performScrollTo())
        options().performClick()
        val baseline = geometry(row(text(R.string.delete)))
        compose.runOnIdle { assertTrue(removals.isEmpty()) }
        compose.onNodeWithText(text(R.string.delete)).performClick()
        compose.runOnIdle { assertEquals(listOf(alpha), removals) }
        useSubagents()
        assertSameGeometry(optionsBaseline, options().performScrollTo())
        options().performClick()
        assertSameGeometry(baseline, row(text(R.string.delete)))
        compose.runOnIdle { assertEquals(listOf(alpha), removals) }
        compose.onNodeWithText(text(R.string.delete)).performClick()
        compose.runOnIdle { assertEquals(listOf(alpha, alpha), removals) }
    }
}
