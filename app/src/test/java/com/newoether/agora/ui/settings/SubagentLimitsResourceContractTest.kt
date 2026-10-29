package com.newoether.agora.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentLimitsResourceContractTest {
    private val directories = listOf(
        "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
        "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh", "values-zh-rTW",
    )
    private val titles = mapOf(
        "subagents_limits_title" to "Limits",
        "subagents_max_total" to "Total Subagents",
        "subagents_max_concurrent" to "Concurrent Subagents",
    )
    private val descriptions = setOf("subagents_max_total_desc", "subagents_max_concurrent_desc")

    @Test
    fun allSupportedLocalesDefineTranslatedLimitsAndMatchingNumberPlaceholders() {
        val root = locateResources()
        val strings = directories.associateWith { readStrings(File(root, "$it/subagent_strings.xml")) }
        val defaults = strings.getValue("values")
        titles.forEach { (key, expected) -> assertEquals(key, expected, defaults[key]) }
        val required = titles.keys + descriptions + "subagents_limit_value"
        strings.forEach { (directory, values) ->
            required.forEach { key ->
                val value = values[key]
                assertTrue("$directory $key missing", !value.isNullOrBlank())
                assertEquals("$directory $key placeholders", placeholders(defaults.getValue(key)), placeholders(value!!))
                if (directory != "values" && key != "subagents_limit_value") {
                    assertNotEquals("$directory $key English fallback", defaults[key], value)
                }
            }
        }
        assertEquals(setOf("%1\$d"), placeholders(defaults.getValue("subagents_limit_value")))
        assertTrue(defaults.getValue("subagents_max_total_desc").contains("including completed"))
        assertTrue(defaults.getValue("subagents_max_total_desc").contains("does not stop or delete"))
        assertTrue(defaults.getValue("subagents_max_concurrent_desc").contains("per parent conversation"))
        assertTrue(defaults.getValue("subagents_max_concurrent_desc").contains("Extra work waits"))
        assertTrue(defaults.getValue("subagents_max_concurrent_desc").contains("lets running subagents finish"))
    }

    private fun readStrings(file: File): Map<String, String> = buildMap {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        repeat(nodes.length) { index ->
            val node = nodes.item(index)
            val key = node.attributes.getNamedItem("name").nodeValue
            assertFalse("${file.parentFile.name} duplicate $key", containsKey(key))
            put(key, node.textContent)
        }
    }

    private fun placeholders(value: String): Set<String> =
        Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.toSet()

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
