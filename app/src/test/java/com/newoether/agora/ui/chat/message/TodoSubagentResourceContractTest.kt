package com.newoether.agora.ui.chat.message

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoSubagentResourceContractTest {
    private val directories = listOf(
        "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
        "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh", "values-zh-rTW",
    )
    private val titles = mapOf(
        "tool_read_todo" to "Read Todo List",
        "tool_update_todo" to "Update Todo List",
        "tool_spawn_agent" to "Start Subagent",
        "tool_send_agent_message" to "Send Message to Subagent",
        "tool_wait_agents" to "Wait for Subagents",
        "tool_list_agents" to "List Subagents",
        "tool_stop_agent" to "Stop Subagent",
        "settings_todo" to "Todo List",
        "todo_enable" to "Enable Todo List",
        "settings_subagents" to "Subagents",
        "subagents_enable" to "Enable Subagents",
    )
    private val active = setOf(
        "tool_reading_todo", "tool_updating_todo", "tool_spawning_agent", "tool_sending_agent_message",
        "tool_waiting_agents", "tool_listing_agents", "tool_stopping_agent",
    )
    private val terminal = setOf(
        "tool_read_todo_done", "tool_updated_todo", "tool_spawned_agent", "tool_queued_agent_message",
        "tool_waited_agents", "tool_listed_agents", "tool_stopped_agent", "tool_todo_empty",
        "tool_agents_empty", "tool_agent_results_empty",
    )
    private val other = setOf(
        "settings_todo_desc", "todo_enable_desc", "settings_subagents_desc", "subagents_enable_desc",
        "message_source_subagent", "subagent_running", "subagent_completed", "subagent_failed", "subagent_stopped",
        "subagent_idle", "todo_title", "todo_progress", "todo_pending", "todo_in_progress", "todo_completed",
    )

    @Test
    fun allLocalesContainLocalizedTitlesSummariesSourcesSettingsAndStatuses() {
        val root = locateResources()
        val resources = directories.associateWith { readStrings(File(root, it)) }
        val defaults = resources.getValue("values")
        titles.forEach { (key, title) -> assertEquals(key, title, defaults[key]) }
        val keys = titles.keys + active + terminal + other
        resources.forEach { (directory, values) ->
            keys.forEach { key ->
                val value = values[key]
                assertTrue("$directory $key missing", !value.isNullOrBlank())
                assertEquals("$directory $key placeholders", placeholders(defaults.getValue(key)), placeholders(value!!))
                if (directory != "values" && key != "todo_progress") {
                    assertNotEquals("$directory $key English fallback", defaults[key], value)
                }
            }
            active.forEach { key ->
                assertTrue("$directory $key ellipsis", values.getValue(key).endsWith("\u2026"))
                assertFalse("$directory $key ASCII ellipsis", values.getValue(key).contains("..."))
            }
            terminal.forEach { key ->
                assertFalse("$directory $key terminal period", values.getValue(key).endsWith("."))
            }
        }
        assertEquals("子代理", resources.getValue("values-zh")["message_source_subagent"])
        assertEquals("子代理", resources.getValue("values-zh-rTW")["message_source_subagent"])
    }

    @Test
    fun localesKeepMergedKeyParityWithoutDuplicateDefinitions() {
        val root = locateResources()
        val expected = readStrings(File(root, "values")).keys
        directories.drop(1).forEach { directory ->
            assertEquals("$directory merged key parity", expected, readStrings(File(root, directory)).keys)
        }
    }

    @Test
    fun subagentCountPluralsAreLocalizedWithLocaleQuantitiesAndOneCountPlaceholder() {
        val root = locateResources()
        val resources = directories.associateWith { directory ->
            buildMap<String, Map<String, String>> {
                File(root, directory).listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    val nodes = document.getElementsByTagName("plurals")
                    repeat(nodes.length) { index ->
                        val plural = nodes.item(index) as org.w3c.dom.Element
                        val name = plural.getAttribute("name")
                        assertFalse("$directory duplicate plural $name", containsKey(name))
                        val items = plural.getElementsByTagName("item")
                        put(name, buildMap {
                            repeat(items.length) { itemIndex ->
                                val item = items.item(itemIndex) as org.w3c.dom.Element
                                val quantity = item.getAttribute("quantity")
                                assertFalse("$directory $name duplicate $quantity", containsKey(quantity))
                                put(quantity, item.textContent)
                            }
                        })
                    }
                }
            }
        }
        val defaults = resources.getValue("values")
        resources.forEach { (directory, plurals) ->
            assertEquals("$directory merged plural key parity", defaults.keys, plurals.keys)
            val total = plurals.getValue("subagents_count")
            val running = plurals.getValue("subagents_running_count")
            assertEquals("$directory count quantities", total.keys, running.keys)
            assertTrue("$directory other quantity", "other" in running)
            running.forEach { (quantity, value) ->
                assertEquals("$directory $quantity count placeholder", 1, Regex("%d").findAll(value).count())
                if (directory != "values") assertNotEquals("$directory English fallback", "%d Running", value)
            }
        }
        assertEquals(mapOf("one" to "%d Running", "other" to "%d Running"),
            defaults.getValue("subagents_running_count"))
        assertEquals(mapOf("one" to "%d Subagent", "other" to "%d Subagents"),
            defaults.getValue("subagents_count"))
        assertEquals("%d 个运行中", resources.getValue("values-zh").getValue("subagents_running_count")["other"])
        assertEquals("%d 個執行中", resources.getValue("values-zh-rTW").getValue("subagents_running_count")["other"])
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
