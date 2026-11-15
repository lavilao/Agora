package com.newoether.agora.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.newoether.agora.util.DebugLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin boundary of Mirage, Cactus' on-device image-understanding model.
 *
 * Mirage is a 22 MB `.cact` (HuggingFace `Cactus-Compute/mirage`) that runs on
 * the same prebuilt runtime as Needle 3 and Whistle. It is not a chat model:
 * the runtime describes one picture through `cactus_describe()` — an RGB
 * buffer plus JSON options — and answers with a structured record (scene
 * description, objects, OCR text, relations). [CactusChatEngine] renders that
 * record as text, which is what lets Mirage serve the app's image
 * transcription setting and an attached photo in a conversation.
 *
 * The engine keeps one model per kind, so a resident Mirage archive lives
 * beside the text router and the speech model; describe calls serialize with
 * chat generation on the engine's single native mutex.
 */
internal object CactusVisionEngine {
    private const val TAG = "CactusVisionEngine"

    /** Hard ceiling for the decoded image's longest side, in pixels. */
    private const val MAX_IMAGE_SIDE = 1024

    private external fun nativeDescribe(
        rgb: ByteArray,
        width: Int,
        height: Int,
        options: String?,
        language: String?,
    ): String

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * True when [path] names the installed Mirage archive. This is only a
     * hint: the native loader reports the kind of the archive it actually
     * opened, which covers a renamed or fine-tuned copy.
     */
    fun isVisionModelPath(path: String): Boolean =
        File(path).name.startsWith("mirage", ignoreCase = true)

    /**
     * Describes one image file on the caller's thread, which the chat
     * generation path already owns. Failures arrive as
     * [MirageDescription.error] instead of throwing, so one unreadable image
     * cannot fail the run.
     */
    fun describeBlocking(imagePath: String, maxTokens: Int): MirageDescription {
        val pixels = decodeRgb(imagePath)
            ?: return MirageDescription.failure("unable to read the image")
        return try {
            val raw = nativeDescribe(
                pixels.bytes,
                pixels.width,
                pixels.height,
                optionsJson(maxTokens),
                null,
            )
            MirageDescription.parse(json, raw)
        } catch (e: UnsatisfiedLinkError) {
            MirageDescription.failure("the vision engine is unavailable on this build")
        } catch (e: Throwable) {
            DebugLog.e(TAG, "Mirage describe failed", e)
            MirageDescription.failure(e.message ?: "image description failed")
        }
    }

    /**
     * Ask only for the parts the chat surface can present as text: the scene
     * description, the objects it found and the writing it read. Masks and
     * boxes carry no meaning in a transcript, so they stay out of the request.
     */
    private fun optionsJson(maxTokens: Int): String =
        "{\"sections\":[\"scene\",\"objects\",\"text\"],\"max_tokens\":" +
            maxTokens.coerceIn(64, 1024) + "}"

    private data class RgbPixels(val bytes: ByteArray, val width: Int, val height: Int)

    /** Decodes [imagePath] to packed 8-bit RGB, subsampled to a bounded side. */
    private fun decodeRgb(imagePath: String): RgbPixels? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_IMAGE_SIDE) {
            sampleSize *= 2
        }
        val bitmap = try {
            BitmapFactory.decodeFile(
                imagePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (e: OutOfMemoryError) {
            DebugLog.e(TAG, "Image too large to decode: $imagePath", e)
            null
        } ?: return null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val bytes = ByteArray(width * height * 3)
            for (index in pixels.indices) {
                val argb = pixels[index]
                val offset = index * 3
                bytes[offset] = (argb shr 16).toByte()
                bytes[offset + 1] = (argb shr 8).toByte()
                bytes[offset + 2] = argb.toByte()
            }
            RgbPixels(bytes, width, height)
        } finally {
            bitmap.recycle()
        }
    }
}

/** One Mirage answer: the rendered text, or the engine's error message. */
internal data class MirageDescription(
    val text: String,
    val error: String?,
) {
    companion object {
        fun failure(message: String) = MirageDescription(text = "", error = message)

        fun parse(json: Json, raw: String): MirageDescription {
            val trimmed = raw.trim().trimEnd('\u0000')
            if (trimmed.isEmpty()) return failure("the vision model returned no result")
            val root = try {
                json.parseToJsonElement(trimmed).jsonObject
            } catch (e: Exception) {
                return failure("malformed vision response: ${e.message}")
            }
            root["error"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { return failure(it) }
            val text = CactusVisionTranscript.render(root)
            return if (text.isBlank()) {
                failure("the vision model found nothing to describe")
            } else {
                MirageDescription(text = text, error = null)
            }
        }
    }
}

/**
 * Renders Mirage's structured record as plain transcript text.
 *
 * The record carries a scene description, the objects it found with their
 * attributes, the writing it read, and a short reasoning trace. Only those
 * human-readable parts reach the transcript: boxes, scores and masks stay in
 * the JSON, where they are of no use to a chat model or a saved transcript.
 */
internal object CactusVisionTranscript {

    fun render(root: JsonObject): String {
        val parts = mutableListOf<String>()
        root.stringOf("description")
            ?.takeIf { it.isNotBlank() }
            ?.let(parts::add)

        val objects = root.namedEntries("objects", listOf("name", "label"))
        if (objects.isNotEmpty()) {
            parts.add("Objects: " + objects.joinToString(", "))
        }
        val texts = root.namedEntries("text", listOf("text", "value", "content"))
        if (texts.isNotEmpty()) {
            parts.add("Text in the image:")
            texts.forEach { parts.add("- $it") }
        }
        val relations = root.namedEntries("relations", listOf("description", "label", "relation"))
        if (relations.isNotEmpty()) {
            parts.add("Relations: " + relations.joinToString("; "))
        }
        return parts.joinToString("\n")
    }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Names of the entries of [key], preferring the first key in [fields] that
     * carries a non-blank string. An array of plain strings is accepted too, so
     * a schema that flattens its entries still renders.
     */
    private fun JsonObject.namedEntries(key: String, fields: List<String>): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() && it != "null" }
                is JsonObject -> fields.firstNotNullOfOrNull { field ->
                    element.stringOf(field)?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }
    }
}
