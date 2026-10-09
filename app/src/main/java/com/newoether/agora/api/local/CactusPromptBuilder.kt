package com.newoether.agora.api.local

import com.newoether.agora.api.CactusChatTurn
import com.newoether.agora.api.CactusTurnToolCall
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Converts a resolved conversation into the message JSON the Cactus engine
 * consumes (OpenAI-ish: role/content/images, assistant tool_calls and tool
 * result turns carry the call identity through the "name" field).
 *
 * Images are passed as absolute file paths inside each user turn — the engine
 * embeds them through the bundle's own vision tower, so models such as
 * Gemma-4-E2B answer with vision without an mmproj companion.
 */
internal object CactusPromptBuilder {

    private val schemaJson = Json { ignoreUnknownKeys = true }

    fun buildTurns(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        imagePathsOut: MutableList<String>? = null,
    ): List<CactusChatTurn> {
        val turns = mutableListOf<CactusChatTurn>()

        if (!systemPrompt.isNullOrBlank()) {
            turns.add(CactusChatTurn(role = "system", content = systemPrompt))
        }

        for (msg in messages) {
            if (msg.participant == Participant.ERROR) continue

            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                val calls = if (!toolSegs.isNullOrEmpty()) {
                    toolSegs.map { seg ->
                        val name = seg.toolName.orEmpty()
                        val arguments = seg.toolArgs ?: "{}"
                        CactusTurnToolCall(
                            id = seg.toolCallId?.takeIf(String::isNotBlank)
                                ?: buildToolCallId(name, arguments),
                            name = name,
                            argumentsJson = arguments,
                        )
                    }
                } else {
                    msg.toolCall?.let { toolCall ->
                        listOf(
                            CactusTurnToolCall(
                                id = toolCall.toolCallId?.takeIf(String::isNotBlank)
                                    ?: buildToolCallId(toolCall.toolName, toolCall.arguments),
                                name = toolCall.toolName,
                                argumentsJson = toolCall.arguments,
                            ),
                        )
                    }.orEmpty()
                }
                if (calls.isNotEmpty()) {
                    turns.add(
                        CactusChatTurn(role = "assistant", content = "", toolCalls = calls),
                    )
                }
                continue
            }

            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        val name = seg.toolName.orEmpty()
                        val arguments = seg.toolArgs ?: "{}"
                        turns.add(
                            CactusChatTurn(
                                role = "tool",
                                content = seg.toolResult.orEmpty(),
                                toolName = name,
                            ),
                        )
                    }
                } else {
                    msg.toolCall?.let { toolCall ->
                        turns.add(
                            CactusChatTurn(
                                role = "tool",
                                content = toolCall.result,
                                toolName = toolCall.toolName,
                            ),
                        )
                    }
                }
                continue
            }

            val role = when (msg.participant) {
                Participant.USER -> "user"
                Participant.MODEL -> "assistant"
                Participant.ERROR -> "user"
            }

            val images = msg.images.filter { it.isNotBlank() }
            if (role == "user" && images.isNotEmpty()) {
                imagePathsOut?.addAll(images)
            }
            turns.add(
                CactusChatTurn(
                    role = role,
                    content = msg.text,
                    images = if (role == "user") images else emptyList(),
                ),
            )
        }

        return turns
    }

    /**
     * OpenAI function-tool JSON understood by the engine's tool constraints.
     * ToolParameters keeps the MCP schema verbatim, and the engine forwards it
     * into the constraint grammar, so nested schemas survive unchanged.
     */
    fun buildToolsJson(
        tools: List<com.newoether.agora.api.ToolDefinition>,
    ): String = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.function.name)
                    put("description", tool.function.description)
                    put(
                        "parameters",
                        schemaJson.encodeToJsonElement(
                            com.newoether.agora.api.ToolParameters.serializer(),
                            tool.function.parameters,
                        ).jsonObject,
                    )
                })
            })
        }
    }.toString()
}
