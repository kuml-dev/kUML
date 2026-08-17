package dev.kuml.jetbrains.markdown

import dev.kuml.jetbrains.KumlPreviewRenderer
import dev.kuml.jetbrains.KumlPreviewSettings
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Thread-safe LRU cache for rendered Markdown kUML diagram previews.
 *
 * Keys are computed from the SHA-256 hash of script text, theme, and diagram name.
 * Caches up to [MAX_ENTRIES] entries (default 50) to keep memory bounded.
 */
internal object KumlMarkdownPreviewCache {
    const val MAX_ENTRIES: Int = 50

    private val cache: MutableMap<String, KumlPreviewRenderer.Outcome> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, KumlPreviewRenderer.Outcome>(MAX_ENTRIES, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KumlPreviewRenderer.Outcome>?): Boolean =
                    size > MAX_ENTRIES
            },
        )

    /**
     * Computes the SHA-256 cache key for a given [scriptText], [theme], and [name].
     */
    fun computeKey(
        scriptText: String,
        theme: String,
        name: String = "",
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(scriptText.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(theme.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(name.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Looks up or renders the kUML diagram for the given script content.
     */
    fun getOrRender(
        scriptText: String,
        theme: String = KumlPreviewSettings.theme(),
        baseName: String = "markdown-diagram",
    ): KumlPreviewRenderer.Outcome {
        val key = computeKey(scriptText, theme, baseName)
        cache[key]?.let { return it }

        val outcome =
            if (scriptText.isBlank()) {
                KumlPreviewRenderer.Outcome.Empty
            } else {
                KumlPreviewRenderer.renderOutcome(
                    scriptText = scriptText,
                    scriptName = "$baseName.kuml.kts",
                    theme = theme,
                )
            }

        cache[key] = outcome
        return outcome
    }

    /** Returns the cached outcome for [key] if present. */
    fun get(key: String): KumlPreviewRenderer.Outcome? = cache[key]

    /** Puts a pre-calculated outcome into the cache. */
    fun put(
        key: String,
        outcome: KumlPreviewRenderer.Outcome,
    ) {
        cache[key] = outcome
    }

    /** Number of elements currently in the cache. */
    fun size(): Int = cache.size

    /** Clears all entries from the cache. */
    fun clear() {
        cache.clear()
    }
}
