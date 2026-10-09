package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import com.newoether.agora.api.CactusEngine
import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.DebugLog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Installs ".cactus" bundle directories for the Cactus engine.
 *
 * Bundles are downloaded from HuggingFace (org Cactus-Compute) as "-cqN" zip
 * archives carrying config.txt, vocab.txt, components/manifest.json, *.weights
 * and the tokenizer sidecars. The download path mirrors the upstream CLI:
 * resolve the newest weight tag that is <= the vendored runtime version, fetch
 * the archive's LFS size/sha256 metadata, stream the file while hashing it,
 * then extract behind zip-slip guards, promote a single top-level folder and
 * validate the layout before anything becomes visible to the engine.
 */
internal class CactusBundleManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Root directory holding every installed cactus model. */
    fun bundlesRoot(): File = File(context.filesDir, "cactus")

    /** Directory names under the root that look like installed bundles. */
    fun installedBundleDirNames(): Set<String> {
        val root = bundlesRoot()
        if (!root.isDirectory) return emptySet()
        return root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "config.txt").isFile }
            .mapNotNull { it.name }
            .toSet()
    }

    /** ".cact" files under the root that look like installed needle models. */
    fun installedActFilenames(): Set<String> {
        val root = bundlesRoot()
        if (!root.isDirectory) return emptySet()
        return root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".cact") && it.length() > 0 }
            .mapNotNull { it.name }
            .toSet()
    }

    /** Target file for one ".cact" catalog variant. */
    fun actFile(entry: CactusModelCatalog.Entry, variant: CactusModelCatalog.Variant): File =
        File(bundlesRoot(), variant.filename)

    fun isActInstalled(entry: CactusModelCatalog.Entry, variant: CactusModelCatalog.Variant): Boolean =
        actFile(entry, variant).isFile

    fun isInstalled(entry: CactusModelCatalog.Entry, variant: CactusModelCatalog.Variant): Boolean =
        installedBundleDirNames().contains(CactusModelCatalog.bundleDirName(entry, variant))

    fun bundleDir(entry: CactusModelCatalog.Entry, variant: CactusModelCatalog.Variant): File =
        File(bundlesRoot(), CactusModelCatalog.bundleDirName(entry, variant))

    /**
     * Live variant resolution against the HuggingFace API; falls back to the
     * pinned catalog values when the network is unavailable. The resolver
     * never accepts a tag newer than the vendored runtime version.
     */
    suspend fun resolveVariant(
        entry: CactusModelCatalog.Entry,
        variant: CactusModelCatalog.Variant,
    ): ResolvedVariant = withContext(Dispatchers.IO) {
        val pinned = ResolvedVariant(
            revision = entry.pinnedRevision,
            filename = variant.filename,
            sizeBytes = variant.sizeBytes,
            sha256 = variant.sha256,
        )
        try {
            val revision = resolveRevision(entry)
            val files = fetchRepoFiles(entry.repoId, revision)
            val match = files.firstOrNull { it.filename == variant.filename }
                ?: return@withContext pinned
            ResolvedVariant(
                revision = revision,
                filename = variant.filename,
                sizeBytes = match.size ?: variant.sizeBytes,
                sha256 = match.sha256 ?: variant.sha256,
            )
        } catch (e: Exception) {
            DebugLog.d(TAG, "Falling back to pinned catalog metadata for ${entry.slug}")
            pinned
        }
    }

    private suspend fun resolveRevision(entry: CactusModelCatalog.Entry): String {
        if (entry.tracksMain) return "main"
        val tags = fetchVersionTags(entry.repoId)
        val eligible = tags
            .filter { (_, version) ->
                CactusModelCatalog.isAtMost(version, CactusModelCatalog.runtimeVersion)
            }
            .sortedWith(
                compareBy(
                    { (_, version) -> version.first },
                    { (_, version) -> version.second },
                    { (_, version) -> version.third },
                ),
            )
        return eligible.lastOrNull()?.first ?: "main"
    }

    private data class RepoFile(val filename: String, val size: Long?, val sha256: String?)

    private suspend fun fetchRepoFiles(repoId: String, revision: String): List<RepoFile> {
        val url = "https://huggingface.co/api/models/${repoId.encodePath()}?blobs=true" +
            "&revision=${revision.encodePath()}"
        val body = executeForBody(url)
        val root = json.parseToJsonElement(body).jsonObject
        return root["siblings"]?.jsonArray?.mapNotNull { sibling ->
            val file = sibling.jsonObject
            val name = file["rfilename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val lfs = file["lfs"]?.jsonObject
            val size = lfs?.get("size")?.jsonPrimitive?.longOrNull
                ?: file["size"]?.jsonPrimitive?.longOrNull
            val sha = lfs?.get("sha256")?.jsonPrimitive?.content
            RepoFile(filename = name, size = size, sha256 = sha)
        }.orEmpty()
    }

    private suspend fun fetchVersionTags(repoId: String): List<Pair<String, Triple<Int, Int, Int>>> {
        val url = "https://huggingface.co/api/models/${repoId.encodePath()}/refs"
        val body = executeForBody(url)
        val root = json.parseToJsonElement(body).jsonObject
        return root["tags"]?.jsonArray?.mapNotNull { tag ->
            val name = tag.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            CactusModelCatalog.parseVersionTag(name)?.let { name to it }
        }.orEmpty()
    }

    private fun executeForBody(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "agora-cactus-downloader")
            .build()
        HttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    /**
     * Streams the variant archive to a staging directory, verifies its
     * sha256, extracts it safely, validates the bundle and moves it to its
     * final directory. Returns the installed bundle directory.
     */
    suspend fun download(
        entry: CactusModelCatalog.Entry,
        variant: CactusModelCatalog.Variant,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val resolved = resolveVariant(entry, variant)
        val target = bundleDir(entry, variant)
        if (target.isDirectory && File(target, "config.txt").isFile) {
            return@withContext target
        }
        val staging = File(bundlesRoot(), ".staging-${System.nanoTime()}")
        staging.mkdirs()
        try {
            val archive = File(staging, resolved.filename)
            val url = "https://huggingface.co/${entry.repoId}/resolve/" +
                "${resolved.revision.encodePath()}/${resolved.filename.encodePath()}"
            downloadFile(url, archive, resolved.sizeBytes, resolved.sha256, onProgress)
            val extracted = File(staging, "extracted")
            extracted.mkdirs()
            extractZipSafely(archive, extracted)
            promoteSingleRoot(extracted)
            validateBundleOrThrow(extracted)
            if (target.exists()) target.deleteRecursively()
            if (!extracted.renameTo(target)) {
                extracted.copyRecursively(target, overwrite = true)
                extracted.deleteRecursively()
            }
            target
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        expectedSize: Long?,
        expectedSha256: String?,
        onProgress: suspend (Long, Long?) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "agora-cactus-downloader")
            .build()
        HttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading model")
            val body = response.body ?: throw IOException("Empty download body")
            val digest = MessageDigest.getInstance("SHA-256")
            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        currentCoroutineContext().ensureActive()
                        onProgress(copied, expectedSize)
                    }
                    if (expectedSize != null && copied != expectedSize) {
                        throw IOException(
                            "Download incomplete: $copied of $expectedSize bytes",
                        )
                    }
                }
            }
            if (expectedSha256 != null) {
                val actual = digest.digest().toHex()
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException("Model archive checksum mismatch")
                }
            }
        }
    }

    /**
     * Downloads one ".cact" model file for the prebuilt Needle runtime:
     * resolve the pinned revision metadata, stream the file while hashing
     * it into a staging name and promote it atomically. The engine itself
     * validates the container when the model is loaded.
     */
    suspend fun downloadAct(
        entry: CactusModelCatalog.Entry,
        variant: CactusModelCatalog.Variant,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val resolved = resolveVariant(entry, variant)
        val target = actFile(entry, variant)
        if (target.isFile && target.length() == resolved.sizeBytes) {
            return@withContext target
        }
        bundlesRoot().mkdirs()
        val staging = File(bundlesRoot(), ".staging-${System.nanoTime()}")
        staging.mkdirs()
        try {
            val destination = File(staging, variant.filename)
            val url = "https://huggingface.co/${entry.repoId}/resolve/" +
                "${resolved.revision.encodePath()}/${variant.filename.encodePath()}"
            downloadFile(url, destination, resolved.sizeBytes, resolved.sha256, onProgress)
            if (target.exists()) target.delete()
            if (!destination.renameTo(target)) {
                destination.copyTo(target, overwrite = true)
                destination.delete()
            }
            target
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Imports a ".cact" model file picked through SAF, copying it into app
     * storage under a non-conflicting name. Size sanity only: the engine
     * rejects malformed containers at load time with its own diagnostics.
     */
    suspend fun importActFile(
        uri: Uri,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        bundlesRoot().mkdirs()
        val staging = File(bundlesRoot(), ".staging-${System.nanoTime()}")
        staging.mkdirs()
        try {
            val destination = File(staging, "import.cact")
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        currentCoroutineContext().ensureActive()
                        onProgress(copied, null)
                    }
                }
            } ?: throw IOException("Unable to open the selected file")
            if (destination.length() < 1 shl 20 ||
                destination.length() > 512L shl 20
            ) {
                throw IOException("Not a usable .cact model (size ${destination.length()})")
            }
            var name = "needle3.cact"
            var index = 2
            while (File(bundlesRoot(), name).exists()) {
                name = "needle3-${index++}.cact"
            }
            val target = File(bundlesRoot(), name)
            if (!destination.renameTo(target)) {
                destination.copyTo(target, overwrite = true)
                destination.delete()
            }
            target
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Extracts an archive picked through SAF into a fresh bundle directory. */
    suspend fun importZip(
        uri: Uri,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val staging = File(bundlesRoot(), ".staging-${System.nanoTime()}")
        staging.mkdirs()
        try {
            val archive = File(staging, "import.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        onProgress(copied, null)
                    }
                }
            } ?: throw IOException("Unable to open the selected archive")
            val extracted = File(staging, "extracted")
            extracted.mkdirs()
            extractZipSafely(archive, extracted)
            promoteSingleRoot(extracted)
            validateBundleOrThrow(extracted)
            val name = uniqueBundleName(extracted)
            val target = File(bundlesRoot(), name)
            if (!extracted.renameTo(target)) {
                extracted.copyRecursively(target, overwrite = true)
                extracted.deleteRecursively()
            }
            target
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Imports a bundle directory tree picked through SAF. */
    suspend fun importDirectory(uri: Uri): File = withContext(Dispatchers.IO) {
        val staging = File(bundlesRoot(), ".staging-${System.nanoTime()}")
        staging.mkdirs()
        try {
            val extracted = File(staging, "extracted")
            copyDocumentTree(uri, extracted)
            promoteSingleRoot(extracted)
            validateBundleOrThrow(extracted)
            val name = uniqueBundleName(extracted)
            val target = File(bundlesRoot(), name)
            if (!extracted.renameTo(target)) {
                extracted.copyRecursively(target, overwrite = true)
                extracted.deleteRecursively()
            }
            target
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun uniqueBundleName(candidate: File): String {
        val base = guessBundleName(candidate)
        var name = base
        var index = 2
        while (File(bundlesRoot(), name).exists()) {
            name = "$base-${index++}"
        }
        return name
    }

    private fun guessBundleName(bundle: File): String {
        val fromManifest = File(bundle, ".cactus_cq_download.json")
        val fromConfig = File(bundle, "config.txt")
        val candidates = sequenceOf(fromManifest, fromConfig)
        for (file in candidates) {
            if (!file.isFile) continue
            val name = runCatching {
                if (file.extension == "json") {
                    json.parseToJsonElement(file.readText()).jsonObject["local_name"]
                        ?.jsonPrimitive?.content
                } else {
                    file.readLines().firstOrNull { it.startsWith("model_name=") }
                        ?.substringAfter("=")
                }
            }.getOrNull() ?: continue
            val cleaned = name.trim().lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('-')
            if (cleaned.isNotEmpty()) return cleaned
        }
        return "cactus-bundle"
    }

    private fun copyDocumentTree(treeUri: Uri, destination: File) {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Unable to open the selected folder")
        copyDocumentInto(root, destination)
    }

    private fun copyDocumentInto(document: androidx.documentfile.provider.DocumentFile, target: File) {
        if (document.isDirectory) {
            target.mkdirs()
            for (child in document.listFiles()) {
                val name = child.name ?: continue
                copyDocumentInto(child, File(target, name))
            }
        } else {
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(document.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Unable to read ${document.name}")
        }
    }

    private fun extractZipSafely(archive: File, outputDir: File) {
        val canonicalRoot = outputDir.canonicalFile.toPath()
        java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry: java.util.zip.ZipEntry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    continue
                }
                // Zip-slip: every member must stay inside the extraction root.
                // (Android's ZipEntry does not expose unix mode bits, and
                // java.util.zip can only write regular files — a zip "symlink"
                // member is extracted as a plain file containing the target
                // path, so the containment guard below is the real defense,
                // matching the protection level of the upstream CLI.)
                val target = File(outputDir, entry.name)
                if (!target.canonicalFile.toPath().startsWith(canonicalRoot)) {
                    throw IOException("Unsafe path in archive: ${entry.name}")
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    zip.copyTo(output)
                }
                zip.closeEntry()
            }
        }
    }

    /** If the archive had one top-level directory, hoist its children. */
    private fun promoteSingleRoot(dir: File) {
        if (File(dir, "config.txt").isFile) return
        val children = dir.listFiles()?.filter { it.name != "__MACOSX" }.orEmpty()
        if (children.size == 1 && children[0].isDirectory &&
            File(children[0], "config.txt").isFile
        ) {
            val nested = children[0]
            nested.listFiles()?.forEach { child ->
                val target = File(dir, child.name)
                if (target.exists()) throw IOException("Archive layout conflict: ${child.name}")
                if (!child.renameTo(target)) {
                    child.copyRecursively(target, overwrite = true)
                    child.deleteRecursively()
                }
            }
            nested.delete()
        }
    }

    private fun validateBundleOrThrow(dir: File) {
        validateBundle(dir)?.let { throw IOException(it) }
    }

    /** Recursively deletes a bundle directory or a ".cact" model file from app storage. */
    fun deleteBundle(path: String) {
        val target = File(path)
        val root = bundlesRoot()
        if (!target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) return
        if (target.isDirectory) {
            target.deleteRecursively()
        } else if (target.isFile) {
            target.delete()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.encodePath(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    /** A catalog variant resolved against a concrete HuggingFace revision. */
    data class ResolvedVariant(
        val revision: String,
        val filename: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    companion object {
        private const val TAG = "CactusBundleManager"

        /**
         * Validates an extracted bundle layout the way the engine's downloader
         * does: required files, at least one weights file, and the tokenizer
         * sidecars implied by tokenizer_config.txt. Returns null when valid,
         * otherwise a human-readable reason.
         */
        fun validateBundle(dir: File): String? {
            if (!dir.isDirectory) return "Not a directory"
            val required = listOf("config.txt", "vocab.txt", "components/manifest.json")
            val missing = required.filter { !File(dir, it).isFile }
            if (missing.isNotEmpty()) {
                return "Missing required file(s): ${missing.joinToString()}"
            }
            val hasWeights = dir.walkTopDown().any { it.isFile && it.extension == "weights" }
            if (!hasWeights) return "Bundle contains no weight files"
            val tokenizerConfig = File(dir, "tokenizer_config.txt")
            if (!tokenizerConfig.isFile) return null
            val config = tokenizerConfig.readLines()
                .filter { it.contains("=") && !it.trimStart().startsWith("#") }
                .associate {
                    val index = it.indexOf('=')
                    it.substring(0, index).trim() to it.substring(index + 1).trim()
                }
            val tokenizerType = config["tokenizer_type"]?.lowercase(Locale.ROOT).orEmpty()
            val sidecars = mutableListOf("special_tokens.json")
            if (tokenizerType == "sentencepiece") {
                sidecars.add("merges.txt")
            } else {
                sidecars.add("tokenizer.json")
                if (tokenizerType == "bpe") sidecars.add("merges.txt")
            }
            val missingSidecars = sidecars.filter { !File(dir, it).isFile }
            if (missingSidecars.isNotEmpty()) {
                return "Missing tokenizer sidecar file(s): ${missingSidecars.joinToString()}"
            }
            return null
        }
    }
}
