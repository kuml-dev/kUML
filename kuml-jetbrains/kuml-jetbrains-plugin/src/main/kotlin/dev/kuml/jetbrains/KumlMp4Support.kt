package dev.kuml.jetbrains

/**
 * Probes the system PATH for `ffmpeg`, the sole supported MP4/H.264 encoder backend.
 *
 * Mirrors [KumlWebpSupport] without depending on `kuml-io-anim` (see that class's
 * KDoc for why the platform module avoids that dependency).
 *
 * The result is cached after the first call so the file-system probe runs at
 * most once per JVM lifetime (plugin lifetime in practice).
 */
internal object KumlMp4Support {
    /** `true` when `ffmpeg` is reachable on PATH. */
    val isAvailable: Boolean by lazy { isOnPath("ffmpeg") }

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
