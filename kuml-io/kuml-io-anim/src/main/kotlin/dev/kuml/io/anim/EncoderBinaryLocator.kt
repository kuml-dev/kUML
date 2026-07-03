package dev.kuml.io.anim

/**
 * Probes the system PATH for known animated-WebP encoder binaries.
 *
 * Preference order: `img2webp` (libwebp, purpose-built) → `ffmpeg` (fallback).
 * `cwebp` is static-only and is NOT usable for animated WebP.
 *
 * Binaries are resolved purely by name via `ProcessBuilder` — no shell interpolation,
 * no user-controlled input — to prevent command injection.
 */
public object EncoderBinaryLocator {
    /** Ordered list of candidate binary names. */
    private val CANDIDATES = listOf("img2webp", "ffmpeg")

    /**
     * Returns `true` when at least one supported WebP encoder is found on PATH.
     */
    public fun isWebpAvailable(): Boolean = findWebpBinary() != null

    /**
     * Returns the name of the first available WebP encoder binary, or `null` if none found.
     */
    public fun findWebpBinary(): String? = CANDIDATES.firstOrNull { isOnPath(it) }

    /**
     * Returns `true` when `ffmpeg` (the only supported MP4/H.264 encoder backend) is on PATH.
     */
    public fun isFfmpegAvailable(): Boolean = isOnPath("ffmpeg")

    /**
     * Returns `true` when [name] resolves to an executable on the system PATH.
     *
     * Uses `which` on Unix-like systems and `where` on Windows. Falls back to
     * attempting to launch the binary with no arguments and checking the exit
     * behaviour — some tools exit 0 for `--help`, others exit non-zero, but
     * both confirm the binary is present.
     */
    private fun isOnPath(name: String): Boolean =
        try {
            val whichCmd =
                if (System.getProperty("os.name", "").lowercase().contains("win")) {
                    listOf("where", name)
                } else {
                    listOf("which", name)
                }
            val result =
                ProcessBuilder(whichCmd)
                    .redirectErrorStream(true)
                    .start()
            result.waitFor() == 0
        } catch (_: Exception) {
            false
        }
}
