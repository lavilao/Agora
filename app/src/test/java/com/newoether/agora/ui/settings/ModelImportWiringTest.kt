package com.newoether.agora.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ModelImportWiringTest {
    @Test fun localChatAndProjectorUseTheSharedImportStates() {
        val editor = source("LocalChatModelEditor.kt")
        assertEquals(2, Regex("ModelImportItem\\(").findAll(editor).count())
        assertTrue(editor.contains("R.string.local_chat_import_title"))
        assertTrue(editor.contains("R.string.local_chat_import_desc"))
        assertTrue(editor.contains("title = stringResource(R.string.local_projector_title)"))
        assertTrue(editor.contains("filePath = draft.projectorPath"))
        assertTrue(editor.contains("filePath = draft.modelPath"))
        assertTrue(editor.contains("emptyIcon = Icons.Default.VisibilityOff"))
        assertTrue(editor.contains("importedEffect = stringResource(R.string.local_projector_enabled)"))
        assertTrue(editor.contains("SettingsIconContent(icon = Icons.Default.Tag)"))
        assertTrue(editor.contains("description = stringResource(R.string.local_projector_desc)"))
        assertTrue(editor.contains("importing = importingProjector == false"))
        assertTrue(editor.contains("importing = importingProjector == true"))
        assertTrue(editor.contains("onClick = { pick(false) }"))
        assertTrue(editor.contains("onClick = { pick(true) }"))
        assertFalse(editor.contains("CircularProgressIndicator"))
        assertTrue(editor.contains("imports.discard(draft.projectorPath)"))
        assertTrue(editor.contains("enabled = !busy && !closed"))
    }

    @Test fun importRowsRetainExplicitSaveAndExistingFileOwnership() {
        val editor = source("LocalChatModelEditor.kt")
        assertTrue(editor.contains("if (initial == null)"))
        assertTrue(editor.contains("IconButton(onClick = ::save, enabled = !busy && !closed)"))
        assertTrue(editor.contains("hasUnsavedChanges = draft != start || busy"))
        assertTrue(editor.contains("imports.transfer(config.localFilePath, config.mmprojPath)"))
        assertTrue(editor.contains("viewModel.modelManager.isLocalModelIdTaken(id, excludeId = initial?.id)"))
        assertTrue(editor.contains("onBack = guardedBack"))
    }

    @Test fun localizedImportTitlesKeepTheRequestedFileExtensions() {
        val files = importResourceFiles()
        val expectedLocales = setOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh", "values-zh-rTW"
        )
        assertTrue(files.map { it.parentFile.name }.containsAll(expectedLocales))
        val suffixes = mapOf(
            "embedding_import_title" to " (.gguf)",
            "local_chat_import_title" to " (.gguf)"
        )
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (file in files) {
            val nodes = builder.parse(file).getElementsByTagName("string")
            val strings = (0 until nodes.length).map { nodes.item(it) as Element }
            for (name in listOf("model_import_not_imported", "model_import_file_missing", "local_projector_enabled")) {
                assertEquals("${file.path}: $name", 1, strings.count { it.getAttribute("name") == name })
            }
            assertFalse(strings.any { it.getAttribute("name") == "local_projector_import_title" })
            for ((name, suffix) in suffixes) {
                val matches = strings.filter { it.getAttribute("name") == name }
                assertEquals("${file.path}: $name must occur once", 1, matches.size)
                val value = matches.single().textContent
                assertTrue("${file.path}: $name must end with $suffix", value.endsWith(suffix))
                val title = value.removeSuffix(suffix)
                assertTrue("${file.path}: $name must keep its title", title.isNotBlank())
                assertFalse("${file.path}: $name must not repeat $suffix", title.contains(suffix))
            }
        }
    }

    private fun importResourceFiles(): List<File> {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            for (root in listOf("app/src/main/res", "src/main/res")) {
                val resources = File(directory, root)
                if (resources.isDirectory) {
                    return resources.listFiles().orEmpty()
                        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
                        .map { File(it, "model_import_strings.xml") }
                        .filter { it.isFile }
                        .sortedBy { it.parentFile.name }
                }
            }
            directory = directory.parentFile ?: error("Unable to locate model import resources")
        }
        error("Unable to locate model import resources")
    }

    private fun source(name: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            for (root in listOf("app/src/main/java", "src/main/java")) {
                val file = File(directory, "$root/com/newoether/agora/ui/settings/$name")
                if (file.isFile) return file.readText().replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: error("Unable to locate $name")
        }
        error("Unable to locate $name")
    }
}
