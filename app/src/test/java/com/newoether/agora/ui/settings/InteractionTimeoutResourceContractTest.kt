package com.newoether.agora.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionTimeoutResourceContractTest {
    private val directories = listOf(
        "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
        "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh", "values-zh-rTW",
    )
    private val keys = setOf("ask_user_timeout", "ask_user_timeout_desc", "ask_user_timeout_minutes")

    @Test
    fun everySupportedLocaleDefinesTimeoutCopyWithMatchingDurationPlaceholder() {
        val root = locateResources()
        val resources = directories.associateWith { readStrings(File(root, it)) }
        val defaults = resources.getValue("values")
        assertEquals("Timeout", defaults["ask_user_timeout"])
        assertEquals("%1\$d min", defaults["ask_user_timeout_minutes"])
        assertTrue(defaults.getValue("ask_user_timeout_desc").contains("blocking question set"))
        assertTrue(defaults.getValue("ask_user_timeout_desc").contains("Unanswered questions are skipped"))
        resources.forEach { (directory, strings) ->
            keys.forEach { key ->
                val value = strings[key]
                assertTrue("$directory $key missing", !value.isNullOrBlank())
                assertEquals("$directory $key placeholders", placeholders(defaults.getValue(key)), placeholders(value!!))
                if (directory != "values" && key != "ask_user_timeout_minutes") {
                    assertNotEquals("$directory $key English fallback", defaults[key], value)
                }
            }
            assertEquals(
                "$directory duration uses one numeric placeholder",
                listOf("%1\$d"), placeholders(strings.getValue("ask_user_timeout_minutes")),
            )
        }
    }

    @Test
    fun supportedLocalesKeepMergedStringKeysInParityWithoutDuplicateDefinitions() {
        val root = locateResources()
        val defaults = readStrings(File(root, "values")).keys
        directories.drop(1).forEach { directory ->
            assertEquals("$directory merged key parity", defaults, readStrings(File(root, directory)).keys)
        }
    }

    private fun readStrings(directory: File): Map<String, String> = buildMap {
        directory.listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            repeat(nodes.length) { index ->
                val node = nodes.item(index)
                val key = node.attributes.getNamedItem("name").nodeValue
                assertFalse("${directory.name} duplicate $key", containsKey(key))
                put(key, node.textContent)
            }
        }
    }

    private fun placeholders(value: String): List<String> =
        Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.toList()

    private fun locateResources(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(File(directory, "app/src/main/res"), File(directory, "src/main/res"))
                .firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate resource directory")
    }
}
