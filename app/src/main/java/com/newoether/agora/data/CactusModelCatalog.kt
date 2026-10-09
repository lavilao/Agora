package com.newoether.agora.data

import com.newoether.agora.api.CactusEngine

/**
 * Curated catalog of Cactus engine models that publish prebuilt "-cqN" bundles
 * the vendored runtime can load. Each entry pins the HuggingFace revision and
 * the archive checksums observed for the vendored [CactusEngine.UPSTREAM_VERSION];
 * [CactusBundleManager] still re-resolves them live so updated weight tags are
 * picked up, and falls back to the pinned values when offline.
 *
 * Weight tags are only re-published on HuggingFace when the format changes, so
 * the revision pinned here can trail the runtime version (gemma-4-E2B-it has
 * not needed a new tag since v2.0.1).
 */
internal object CactusModelCatalog {

    /** One downloadable CQ variant of a model family. */
    data class Variant(
        val bits: Double,
        val filename: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    /** One model family entry in the catalog. */
    data class Entry(
        /** HuggingFace repository under the Cactus-Compute organization. */
        val repoId: String,
        /** Short localized-agnostic identifier, e.g. "gemma-4-e2b-it". */
        val slug: String,
        /** Revision tag to use when the live resolver has nothing newer. */
        val pinnedRevision: String,
        /** True when the family ships no version tags and lives on main. */
        val tracksMain: Boolean = false,
        /** Ordered variants, first is the recommended default. */
        val variants: List<Variant>,
    ) {
        val defaultVariant: Variant get() = variants.first()
    }

    const val ORG = "Cactus-Compute"

    val entries: List<Entry> = listOf(
        Entry(
            repoId = "$ORG/gemma-4-E2B-it",
            slug = "gemma-4-e2b-it",
            pinnedRevision = "v2.0.1",
            variants = listOf(
                Variant(
                    bits = 4.0,
                    filename = "gemma-4-e2b-it-cq4.zip",
                    sizeBytes = 2_721_189_793L,
                    sha256 = "7a2579ad1089f626b15e121a1fa6a0d8294a4e7f1f7af38823620b5b5a8b8eec",
                ),
                Variant(
                    bits = 3.26,
                    filename = "gemma-4-e2b-it-cq3.26.zip",
                    sizeBytes = 2_574_511_345L,
                    sha256 = "e1ed9958eca01e4b166b9175f0e8c15b72d8295c8e9786c233272c3f0a941f1f",
                ),
                Variant(
                    bits = 2.54,
                    filename = "gemma-4-e2b-it-cq2.54.zip",
                    sizeBytes = 2_407_501_312L,
                    sha256 = "357386b0bf6ba28f7fe856e01b484aa467e8778a79a5911d223a32a430040ef4",
                ),
                Variant(
                    bits = 3.0,
                    filename = "gemma-4-e2b-it-cq3.zip",
                    sizeBytes = 2_519_753_696L,
                    sha256 = "f42dc19ec1d4611af3a116e734f781aed339e80481e8198fdb8c59bf02e5c6dc",
                ),
                Variant(
                    bits = 2.0,
                    filename = "gemma-4-e2b-it-cq2.zip",
                    sizeBytes = 2_278_035_905L,
                    sha256 = "d0cc5bb7e611ae793032f9c7ed1f71023a3ec96111c0e75e44bbfb0c52a4d160",
                ),
            ),
        ),
        Entry(
            repoId = "$ORG/needle",
            slug = "needle",
            pinnedRevision = "main",
            tracksMain = true,
            variants = listOf(
                Variant(
                    bits = 4.0,
                    filename = "needle-cq4.zip",
                    sizeBytes = 16_185_061L,
                    sha256 = "a3423af7d7bd2a35e08ba1f262c4796f4e97963da0a3fbe124d3a8eaae9e4098",
                ),
            ),
        ),
    )

    fun entryForSlug(slug: String): Entry? = entries.firstOrNull { it.slug == slug }

    /** Local directory name for one downloaded variant, e.g. "gemma-4-e2b-it-cq4". */
    fun bundleDirName(entry: Entry, variant: Variant): String {
        val stem = variant.filename.substringBeforeLast(".zip")
        return stem.lowercase()
    }

    /** Suggested model id for a registered bundle. */
    fun suggestedModelId(entry: Entry, variant: Variant): String = bundleDirName(entry, variant)

    /**
     * Parses "v2.0.1"-style tags into comparable triples; null when the ref is
     * not a plain version tag ("main", "experimental", ...).
     */
    fun parseVersionTag(tag: String): Triple<Int, Int, Int>? {
        val body = tag.removePrefix("v").removePrefix("V")
        val parts = body.split(".")
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { part ->
            part.toIntOrNull() ?: return null
        }
        return Triple(
            numbers.getOrElse(0) { 0 },
            numbers.getOrElse(1) { 0 },
            numbers.getOrElse(2) { 0 },
        )
    }

    /** Runtime version as a comparable triple for tag resolution. */
    val runtimeVersion: Triple<Int, Int, Int> =
        parseVersionTag(CactusEngine.UPSTREAM_VERSION) ?: Triple(2, 2, 2)

    /** True when [tag] sorts at or below [other] in semantic version order. */
    fun isAtMost(tag: Triple<Int, Int, Int>, other: Triple<Int, Int, Int>): Boolean =
        (tag.first != other.first && tag.first < other.first) ||
            (tag.first == other.first && tag.second != other.second && tag.second < other.second) ||
            (tag.first == other.first && tag.second == other.second && tag.third <= other.third)
}
