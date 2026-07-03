package dev.kuml.io.anim

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Encodes a list of PNG frames into an MP4 (H.264/yuv420p) file using `ffmpeg`.
 *
 * Unlike [WebpEncoder] (which prefers `img2webp`), MP4/H.264 has no purpose-built
 * lightweight alternative — `ffmpeg` is the only supported backend.
 *
 * ## Alpha-channel limitation
 * H.264 in an MP4 container has no standardised alpha-channel support (unlike APNG
 * or WebP). Encoding a transparent [AnimRenderOptions] would silently drop the
 * alpha channel and composite frames against an arbitrary/undefined background,
 * which is confusing and produces different-looking output than requested.
 * Callers (currently [KumlAnimRenderer]) must reject `options.transparent == true`
 * for MP4 with an actionable [AnimEncoderException] *before* calling [encode] —
 * this class always encodes frames as opaque and does not perform that check
 * itself, since it operates on raw PNG bytes without [AnimRenderOptions] context.
 *
 * Security hardening (mirrors [WebpEncoder]):
 * - Subprocess launched via [ProcessBuilder] with a fixed argument list (no shell, no user input).
 * - Temp directory created with restrictive POSIX permissions (rwx------) and deleted in `finally`.
 * - Subprocess timeout: 120 seconds.
 * - stderr is captured and truncated to 4 KiB to prevent log flooding.
 */
public object Mp4Encoder {
    private const val TIMEOUT_SECONDS = 120L
    private const val MAX_STDERR_BYTES = 4096

    private val log: Logger = Logger.getLogger(Mp4Encoder::class.java.name)

    /**
     * Encode [frames] (PNG byte arrays) into an MP4 (H.264/yuv420p) byte array.
     *
     * @param frames List of single-frame PNG byte arrays (length ≥ 1). Frames are assumed to
     *   already be composited against an opaque background — this encoder does not itself
     *   flatten alpha; see the class-level KDoc for why transparent input should be rejected
     *   upstream.
     * @param delayMs Per-frame display delay in milliseconds; converted to an fps value for ffmpeg.
     * @throws AnimEncoderException if `ffmpeg` is not available on PATH or the subprocess fails.
     */
    public fun encode(
        frames: List<ByteArray>,
        delayMs: Long,
    ): ByteArray {
        if (!EncoderBinaryLocator.isFfmpegAvailable()) {
            throw AnimEncoderException(
                "No MP4 encoder found on PATH. Install ffmpeg via 'brew install ffmpeg' on macOS " +
                    "or 'apt-get install ffmpeg' on Debian/Ubuntu.",
            )
        }

        val tempDir = createTempDir()
        return try {
            val outputFile = File(tempDir, "output.mp4")
            encodeWithFfmpeg(frames, delayMs, tempDir, outputFile)
            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw AnimEncoderException(
                    "MP4 encoder 'ffmpeg' ran successfully but produced an empty output file.",
                )
            }
            outputFile.readBytes()
        } finally {
            // See WebpEncoder.encode() for why deleteRecursively() failures are logged, not thrown.
            val deleted = tempDir.deleteRecursively()
            if (!deleted) {
                log.warning(
                    "Mp4Encoder: failed to fully clean up temp directory '${tempDir.absolutePath}'. " +
                        "Temp files may remain (common on Windows when the encoder subprocess " +
                        "releases file handles asynchronously).",
                )
            }
        }
    }

    private fun encodeWithFfmpeg(
        frames: List<ByteArray>,
        delayMs: Long,
        tempDir: File,
        outputFile: File,
    ) {
        val frameFiles =
            frames.mapIndexed { i, png ->
                val f = File(tempDir, "frame%05d.png".format(i))
                f.writeBytes(png)
                f
            }

        val cmd = buildFfmpegCommand(frameFiles, delayMs, outputFile, tempDir)

        val process =
            ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()

        // Drain stdout concurrently to prevent pipe-buffer deadlock (OS buffer ~64 KiB on Linux).
        val stdoutDrainer =
            Thread { process.inputStream.transferTo(java.io.OutputStream.nullOutputStream()) }
                .also {
                    it.isDaemon = true
                    it.start()
                }

        val stderrBytes = process.errorStream.readNBytes(MAX_STDERR_BYTES)
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        stdoutDrainer.join(1_000)

        if (!finished) {
            process.destroyForcibly()
            throw AnimEncoderException(
                "MP4 encoder 'ffmpeg' timed out after ${TIMEOUT_SECONDS}s.",
            )
        }

        if (process.exitValue() != 0) {
            val stderr = String(stderrBytes, Charsets.UTF_8).take(2048)
            throw AnimEncoderException(
                "MP4 encoder 'ffmpeg' exited with code ${process.exitValue()}. Stderr: $stderr",
            )
        }
    }

    private fun buildFfmpegCommand(
        frameFiles: List<File>,
        delayMs: Long,
        outputFile: File,
        tempDir: File,
    ): List<String> {
        val fps = (1000.0 / delayMs).coerceAtLeast(1.0)
        val pattern = File(tempDir, "frame%05d.png").absolutePath
        // -pix_fmt yuv420p: standard-compatible colour space, playable in every major
        //   browser/OS video player (without it, libx264 may default to yuv444p for
        //   RGBA-sourced input, which many players cannot decode).
        // -movflags +faststart: moves the MOOV atom to the front of the file so the
        //   video can start playing before it is fully downloaded (web-friendly MP4).
        return listOf(
            "ffmpeg",
            "-y",
            "-framerate",
            "%.3f".format(fps),
            "-i",
            pattern,
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            "-vf",
            "fps=%.3f".format(fps),
            outputFile.absolutePath,
        )
    }

    private fun createTempDir(): File =
        try {
            val perms = PosixFilePermissions.fromString("rwx------")
            val attr = PosixFilePermissions.asFileAttribute(perms)
            Files.createTempDirectory("kuml-anim-", attr).toFile()
        } catch (_: UnsupportedOperationException) {
            try {
                val dir = Files.createTempDirectory("kuml-anim-").toFile()
                dir.setReadable(true, true)
                dir.setWritable(true, true)
                dir.setExecutable(true, true)
                dir
            } catch (e: IOException) {
                throw AnimEncoderException("Cannot create temp directory: ${e.message}", e)
            }
        }
}
