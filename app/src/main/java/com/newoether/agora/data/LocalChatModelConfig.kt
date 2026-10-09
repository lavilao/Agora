package com.newoether.agora.data

import com.newoether.agora.api.CactusEngine
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LocalChatModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val modelId: String,
    val alias: String,
    val localFilePath: String = "",
    val mmprojPath: String = "",
    /** Small draft GGUF for speculative decoding; must match the model's vocabulary. */
    val draftModelPath: String = "",
    val nCtx: Int = 2048,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 4096,
    /**
     * Inference engine that owns this model: "llama" for GGUF files through
     * llama.cpp, "cactus" for prebuilt .cactus bundle directories. Defaults to
     * llama so previously persisted models keep their original engine.
     */
    val engine: String = ENGINE_LLAMA,
) {
    val isCactus: Boolean get() = engine == CactusEngine.ENGINE_ID

    companion object {
        const val ENGINE_LLAMA = "llama"
    }
}
