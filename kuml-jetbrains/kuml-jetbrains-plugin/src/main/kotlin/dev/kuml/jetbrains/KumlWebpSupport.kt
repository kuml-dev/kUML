package dev.kuml.jetbrains

/**
 * Probes the system PATH for known animated-WebP encoder binaries.
 *
 * Mirrors the logic from `dev.kuml.io.anim.EncoderBinaryLocator` without
 * depending on the `kuml-io-anim` module (which carries heavyweight Batik/
 * ffmpeg-wrapper transitive dependencies that conflict with the IntelliJ
 * platform classpath).
 *
 * Preference order: `img2webp` (libwebp, purpose-built) → `ffmpeg` (fallback).
 * `cwebp` is static-only and cannot produce animated WebP.
 *
 * The result is cached after the first call so the file-system probe runs at
 * most once per JVM lifetime (plugin lifetime in practice).
 */
internal object KumlWebpSupport {
    private val CANDIDATES = listOf("img2webp", "ffmpeg")

    /** `true` when at least one supported WebP encoder is reachable on PATH. */
    val isAvailable: Boolean by lazy { CANDIDATES.any { isOnPath(it) } }

    private fun isOnPath(name: String): Boolean =
        try {
            val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
            val cmd = if (isWindows) listOf("where", name) else listOf("which", name)
            ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        } catch (_: Exception) {
            false
        }
}
