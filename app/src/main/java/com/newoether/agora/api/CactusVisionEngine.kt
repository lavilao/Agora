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
 * fields, objects and regions with their attributes, text read from the image,
 * relations between those entities and any events). [CactusChatEngine] renders
 * that record as text, which is what lets Mirage serve the app's image
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
     * Leaves the record's sections at the engine's default, which is the whole
     * thing: scene, objects, regions, text, relations and events. Naming a
     * subset would drop exactly the parts the transcript now renders, and the
     * budget below bounds the larger answer instead.
     */
    private fun optionsJson(maxTokens: Int): String =
        "{\"max_tokens\":" + maxTokens.coerceIn(256, MAX_RECORD_TOKENS) + "}"

    /**
     * Ceiling for one record. Detection boxes, scores and masks dominate the
     * raw JSON, so a busy photo needs more room than a chat turn; past this
     * the answer costs more to decode than it adds to a transcript.
     */
    private const val MAX_RECORD_TOKENS = 2048

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
 * The record is a small scene graph: scene fields, objects and regions with
 * open-vocabulary names and properties, the writing read from the image,
 * subject/predicate/object relations between those entities and any events.
 * All of it is text a reader can use, so all of it reaches the transcript.
 * Only geometry stays behind: boxes, masks, points and detection scores say
 * where something is in the pixels, which no transcript consumer can use.
 *
 * The generated `reasoning` trace is left out on purpose. It repeats what the
 * fields already state, and Cactus' own evaluation found it did not help a
 * downstream reader.
 */
internal object CactusVisionTranscript {

    fun render(root: JsonObject): String {
        val objects = root.namedEntries("objects")
        val regions = root.namedEntries("regions")
        val names = buildMap {
            (objects + regions).forEach { entry ->
                val id = entry.id ?: return@forEach
                val name = entry.name ?: return@forEach
                put(id, name)
            }
        }

        val parts = mutableListOf<String>()
        root.stringOf("description")
            ?.takeIf { it.isNotBlank() }
            ?.let(parts::add)

        val scene = root.objectOf("scene")?.fields().orEmpty()
            .ifEmpty { listOfNotNull(root.stringOf("scene")) }
            .filter { it.isNotBlank() }
        if (scene.isNotEmpty()) {
            parts.add("Scene: " + scene.joinToString(" · "))
        }

        if (objects.isNotEmpty()) {
            parts.add("Objects:")
            objects.mapTo(parts) { "- " + it.line() }
        }
        if (regions.isNotEmpty()) {
            parts.add("Other regions:")
            regions.mapTo(parts) { "- " + it.line() }
        }

        val texts = root.namedEntries("text", listOf("string", "text", "value", "content"))
            .mapNotNull { it.name }
        if (texts.isNotEmpty()) {
            parts.add("Text in the image:")
            texts.mapTo(parts) { "- $it" }
        }

        val relations = root.linesOf("relations", names)
        if (relations.isNotEmpty()) {
            parts.add("Relations:")
            relations.mapTo(parts) { "- $it" }
        }

        val events = root.linesOf("events", names)
        if (events.isNotEmpty()) {
            parts.add("Events:")
            events.mapTo(parts) { "- $it" }
        }
        return parts.joinToString("\n")
    }

    /** One object or region: its name plus the properties it was given. */
    private data class Entry(
        val id: String?,
        val name: String?,
        val traits: List<String>,
    ) {
        fun line(): String {
            val label = name ?: return traits.joinToString(", ")
            return if (traits.isEmpty()) label else "$label (${traits.joinToString(", ")})"
        }
    }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.objectOf(key: String): JsonObject? = this[key] as? JsonObject

    /** `"setting: indoor"` lines from a flat object such as `scene`. */
    private fun JsonObject.fields(): List<String> =
        entries.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { "$key: $it" }
        }

    /** Property values of a nested `description`/`attributes` object. */
    private fun JsonObject.traitValues(): List<String> =
        entries.mapNotNull { (_, value) ->
            (value as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() && it != "null" }
        }

    private fun JsonObject.reference(key: String, names: Map<String, String>): String? {
        val element = this[key] ?: return null
        val raw = when (element) {
            is JsonPrimitive -> element.content
            is JsonObject -> element.nameOf()
            else -> null
        }?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return names[raw] ?: raw
    }

    private fun JsonObject.nameOf(): String? =
        listOf("name", "label", "string", "id").firstNotNullOfOrNull { field -> stringOf(field) }

    /**
     * Entities of [key]: an array of objects, or plain strings when a schema
     * flattens its entries.
     */
    private fun JsonObject.namedEntries(key: String): List<Entry> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { Entry(id = null, name = it, traits = emptyList()) }
                is JsonObject -> element.entry()
                else -> null
            }
        }
    }

    /** Text entries, preferring the first key in [fields] that carries a string. */
    private fun JsonObject.namedEntries(key: String, fields: List<String>): List<Entry> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { Entry(id = null, name = it, traits = emptyList()) }
                is JsonObject -> {
                    val name = fields.firstNotNullOfOrNull { field -> element.stringOf(field) }
                    name?.let { Entry(id = null, name = it, traits = emptyList()) }
                }
                else -> null
            }
        }
    }

    private fun JsonObject.entry(): Entry? {
        val traits = buildList {
            this@entry.objectOf("description")?.traitValues()?.let(::addAll)
            this@entry.objectOf("attributes")?.traitValues()?.let(::addAll)
            (this@entry["attributes"] as? JsonArray)?.mapNotNullTo(this) { element ->
                (element as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.takeIf { it.isNotBlank() && it != "null" }
            }
            this@entry.stringOf("position")?.let(::add)
        }
        val name = nameOf()
        if (name == null && traits.isEmpty()) return null
        val id = (this["id"] as? JsonPrimitive)?.content?.takeIf { it != "null" }
        return Entry(id = id, name = name, traits = traits)
    }

    /**
     * Relations and events. The engine stores them as a subject, a predicate
     * and an object, with numeric ids pointing into [names]; resolving them
     * here is what turns `{1, attached_to, 8}` into "wine bottle attached to
     * chair" for a reader that never saw the ids.
     */
    private fun JsonObject.linesOf(key: String, names: Map<String, String>): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() && it != "null" }
                is JsonObject -> element.line(names)
                else -> null
            }
        }
    }

    private fun JsonObject.line(names: Map<String, String>): String? {
        val predicate = listOf("predicate", "relation", "label")
            .firstNotNullOfOrNull { field -> stringOf(field) }
        val subject = reference("subject", names)
        val target = reference("object", names)
        if (predicate != null && subject != null && target != null) {
            return "$subject ${predicate.replace('_', ' ')} $target"
        }
        return nameOf()?.takeIf { it.isNotBlank() && it != "null" }
            ?: listOf("description", "text")
                .firstNotNullOfOrNull { field -> stringOf(field) }
    }
}
