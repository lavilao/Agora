package com.newoether.agora.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoSubagentSettingsWiringTest {
    @Test
    fun repositoryUsesDefaultOnFlowsAndPersistsThroughTheSettingsOwner() {
        val repository = source("data/repository/SettingsRepository.kt")
        assertTrue(repository.contains("hot(settingsManager.todoEnabled, true)"))
        assertTrue(repository.contains("hot(settingsManager.subagentToolsEnabled, true)"))
        assertTrue(repository.contains("fun setTodoEnabled(enabled: Boolean) = scope.launch { settingsManager.saveTodoEnabled(enabled) }"))
        assertTrue(repository.contains("fun setSubagentToolsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveSubagentToolsEnabled(enabled) }"))
    }

    @Test
    fun inheritedTaskContextCannotBypassNewGlobalTodoPermissionOrDelegateRecursively() {
        val engine = source("automation/TaskExecutionEngine.kt")
            .substringAfter("val taskContext = (contextOverride ?: captured.context).copy(")
            .substringBefore("val generationSnapshot")
        assertTrue(engine.contains("todoEnabled = (contextOverride?.todoEnabled ?: true) && captured.context.todoEnabled"))
        assertTrue(engine.contains("subagentToolsEnabled = captured.context.subagentToolsEnabled && contextOverride == null"))
        assertTrue(engine.contains("accessPastConversations = captured.context.accessPastConversations && contextOverride == null"))
    }

    @Test
    fun compactKeepsBothToolFamiliesDisabled() {
        val compact = source("viewmodel/ConversationCompactController.kt")
            .substringAfter("private fun GenerationAdmissionSnapshot.forCompact(")
            .substringBefore("private fun retainedTextTransform(")
        assertTrue(compact.contains("todoEnabled = false"))
        assertTrue(compact.contains("subagentToolsEnabled = false"))
    }

    @Test
    fun contextAccountingInvalidatesWhenEitherGlobalToolPermissionChanges() {
        val projection = source("ui/chat/ContextProjectionState.kt")
        assertTrue(projection.contains("val todoEnabled by settings.todoEnabled.collectAsState()"))
        assertTrue(projection.contains("val subagentToolsEnabled by settings.subagentToolsEnabled.collectAsState()"))
        assertTrue(projection.contains("val enabledModels by settings.enabledModels.collectAsState()"))
        assertTrue(projection.contains("val subagentEnabledModels by settings.subagentEnabledModels.collectAsState()"))
        assertTrue(projection.contains("val subagentRecommendedModel by settings.subagentRecommendedModel.collectAsState()"))
        val key = projection.substringAfter("return requestConfiguration + listOf(")
        assertTrue(key.contains("todoEnabled,"))
        assertTrue(key.contains("subagentToolsEnabled,"))
    }

    private fun source(path: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            listOf("app/src/main/java", "src/main/java").forEach { root ->
                val file = File(directory, "$root/com/newoether/agora/$path")
                if (file.isFile) return file.readText().replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate source: $path")
    }
}
