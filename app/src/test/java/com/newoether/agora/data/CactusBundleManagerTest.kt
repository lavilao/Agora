package com.newoether.agora.data

import com.newoether.agora.api.CactusChatTurn
import com.newoether.agora.api.CactusCompletionOptions
import com.newoether.agora.api.CactusCompletionResult
import com.newoether.agora.api.CactusJson
import com.newoether.agora.api.CactusTurnToolCall
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CactusBundleManagerTest {

    private fun newTempDir(): File =
        Files.createTempDirectory("cactus-bundle-test").toFile()

    private fun writeMinimalBundle(dir: File) {
        dir.mkdirs()
        File(dir, "config.txt").writeText("model_type=test\n")
        File(dir, "vocab.txt").writeText("0\t<pad>\n")
        File(dir, "token_embeddings.weights").writeBytes(byteArrayOf(1))
        File(dir, "components").mkdirs()
        File(dir, "components/manifest.json").writeText("{}")
    }

    @Test
    fun `valid bundle passes validation`() {
        val dir = newTempDir()
        writeMinimalBundle(dir)
        assertNull(CactusBundleManager.validateBundle(dir))
        dir.deleteRecursively()
    }

    @Test
    fun `missing manifest is rejected`() {
        val dir = newTempDir()
        writeMinimalBundle(dir)
        File(dir, "components/manifest.json").delete()
        val problem = CactusBundleManager.validateBundle(dir)
        assertNotNull(problem)
        assertTrue(problem!!.contains("manifest.json"))
        dir.deleteRecursively()
    }

    @Test
    fun `bundle without weights is rejected`() {
        val dir = newTempDir()
        writeMinimalBundle(dir)
        File(dir, "token_embeddings.weights").delete()
        val problem = CactusBundleManager.validateBundle(dir)
        assertNotNull(problem)
        assertTrue(problem.contains("weight"))
        dir.deleteRecursively()
    }

    @Test
    fun `bpe tokenizer requires sidecars`() {
        val dir = newTempDir()
        writeMinimalBundle(dir)
        File(dir, "tokenizer_config.txt").writeText("tokenizer_type=bpe\n")
        val problem = CactusBundleManager.validateBundle(dir)
        assertNotNull(problem)
        assertTrue(problem.contains("tokenizer.json"))
        dir.deleteRecursively()
    }

    @Test
    fun `sentencepiece tokenizer needs merges only`() {
        val dir = newTempDir()
        writeMinimalBundle(dir)
        File(dir, "tokenizer_config.txt").writeText("tokenizer_type=sentencepiece\n")
        File(dir, "special_tokens.json").writeText("{}")
        File(dir, "merges.txt").writeText("")
        assertNull(CactusBundleManager.validateBundle(dir))
        dir.deleteRecursively()
    }

    @Test
    fun `nested weights under components are accepted`() {
        val dir = newTempDir()
        writeMinimalBundle(dir)
        File(dir, "token_embeddings.weights").delete()
        File(dir, "components/decoder.weights").writeBytes(byteArrayOf(2))
        assertNull(CactusBundleManager.validateBundle(dir))
        dir.deleteRecursively()
    }
}

class CactusModelCatalogTest {

    @Test
    fun `version tags parse into comparable triples`() {
        assertEquals(Triple(2, 0, 1), CactusModelCatalog.parseVersionTag("v2.0.1"))
        assertEquals(Triple(1, 14, 0), CactusModelCatalog.parseVersionTag("v1.14"))
        assertEquals(Triple(2, 2, 2), CactusModelCatalog.parseVersionTag("2.2.2"))
        assertNull(CactusModelCatalog.parseVersionTag("main"))
        assertNull(CactusModelCatalog.parseVersionTag("v2.0-rc1"))
    }

    @Test
    fun `runtime version never trails catalog pins`() {
        val gemma = CactusModelCatalog.entryForSlug("gemma-4-e2b-it")!!
        val pinned = CactusModelCatalog.parseVersionTag(gemma.pinnedRevision)!!
        assertTrue(CactusModelCatalog.isAtMost(pinned, CactusModelCatalog.runtimeVersion))
        assertTrue(
            CactusModelCatalog.isAtMost(
                CactusModelCatalog.parseVersionTag("v2.0.1")!!,
                CactusModelCatalog.parseVersionTag("v2.2.2")!!,
            ),
        )
        assertTrue(
            CactusModelCatalog.isAtMost(
                CactusModelCatalog.parseVersionTag("v1.14")!!,
                CactusModelCatalog.parseVersionTag("v1.9")!!,
            ).not(),
        )
    }

    @Test
    fun `bundle dir names are slug-based and unique per variant`() {
        val gemma = CactusModelCatalog.entryForSlug("gemma-4-e2b-it")!!
        val names = gemma.variants.map { CactusModelCatalog.bundleDirName(gemma, it) }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { Regex("^[a-z0-9._-]+$").matches(it) })
        assertEquals("gemma-4-e2b-it-cq4", names.first())
    }

    @Test
    fun `every variant pins a sha256 checksum`() {
        CactusModelCatalog.entries.flatMap { it.variants }.forEach { variant ->
            assertEquals(64, variant.sha256.length)
            assertTrue(variant.sizeBytes > 0)
        }
    }
}

class CactusJsonTest {

    @Test
    fun `turns serialize with role content images and tool calls`() {
        val turns = listOf(
            CactusChatTurn(role = "system", content = "You are helpful."),
            CactusChatTurn(
                role = "user",
                content = "Describe this",
                images = listOf("/tmp/a.png", "/tmp/b.jpg"),
            ),
            CactusChatTurn(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    CactusTurnToolCall(id = "call_1", name = "get_weather", argumentsJson = """{"city":"Lima"}"""),
                ),
            ),
            CactusChatTurn(role = "tool", content = "22C", toolName = "get_weather"),
        )
        val parsed = Json.parseToJsonElement(CactusJson.turnsToJson(turns)).jsonArray
        assertEquals(4, parsed.size)

        val user = parsed[1].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/tmp/a.png", "/tmp/b.jpg"),
            user["images"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val assistant = parsed[2].jsonObject
        val call = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals(
            "get_weather",
            call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )

        val tool = parsed[3].jsonObject
        assertEquals("get_weather", tool["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `options always disable cloud handoff and telemetry`() {
        val json = Json.parseToJsonElement(
            CactusCompletionOptions(temperature = 0.5, topP = 0.9, maxTokens = 128).toJson(),
        ).jsonObject
        assertEquals("false", json["auto_handoff"]!!.jsonPrimitive.content)
        assertEquals("false", json["handoff_with_images"]!!.jsonPrimitive.content)
        assertEquals("false", json["telemetry_enabled"]!!.jsonPrimitive.content)
        assertEquals("128", json["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("0.5", json["temperature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `engine response parses usage and function calls`() {
        val raw = """
            {"success":true,"error":null,"cloud_handoff":false,
             "response":"Hi!","thinking":"hmm",
             "function_calls":[{"name":"f","arguments":{"a":1}}],
             "time_to_first_token_ms":45.5,"total_time_ms":100.25,
             "prefill_tps":800.5,"decode_tps":42.25,"ram_usage_mb":300.0,
             "prefill_tokens":10,"decode_tokens":4,"total_tokens":14}
        """.trimIndent()
        val result = CactusCompletionResult.parse(raw)
        assertTrue(result.success)
        assertEquals("Hi!", result.response)
        assertEquals("hmm", result.thinking)
        assertEquals(1, result.functionCalls.size)
        assertEquals("f", result.functionCalls.single().name)
        assertEquals(10, result.prefillTokens)
        assertEquals(4, result.decodeTokens)
        assertEquals(14, result.totalTokens)
        assertEquals(800.5, result.prefillTps, 0.001)
        assertEquals(42.25, result.decodeTps, 0.001)
    }

    @Test
    fun `failure responses surface the engine error`() {
        val result = CactusCompletionResult.parse(
            "{\"success\":false,\"error\":\"weights missing\",\"response\":\"\"}",
        )
        assertNull(result.success.takeIf { it })
        assertEquals("weights missing", result.error)
    }
}
