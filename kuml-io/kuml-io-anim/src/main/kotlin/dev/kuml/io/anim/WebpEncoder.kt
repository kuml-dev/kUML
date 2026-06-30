package dev.kuml.io.anim

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Encodes a list of PNG frames into an animated WebP file using an external binary.
 *
 * Preference order: `img2webp` (libwebp) → `ffmpeg`.
 * If neither is available, throws [AnimEncoderException] with an actionable message.
 *
 * Security hardening:
 * - Subprocess launched via [ProcessBuilder] with a fixed argument list (no shell, no user input).
 * - Temp directory created with restrictive POSIX permissions (rwx------) and deleted in `finally`.
 * - Subprocess timeout: 120 seconds.
 * - stderr is captured and truncated to 4 KiB to prevent log flooding.
 */
public object WebpEncoder {
    private const val TIMEOUT_SECONDS = 120L
    private const val MAX_STDERR_BYTES = 4096

    private val log: Logger = Logger.getLogger(WebpEncoder::class.java.name)

    /**
     * Encode [frames] (PNG byte arrays) into an animated WebP byte array.
     *
     * @param frames List of single-frame PNG byte arrays (length ≥ 1).
     * @param delayMs Per-frame display delay in milliseconds.
     * @param numPlays Loop count (0 = loop forever). Only `img2webp` honours this flag;
     *   the ffmpeg fallback always produces a looping WebP.
     * @throws AnimEncoderException if no encoder binary is available or the subprocess fails.
     */
    public fun encode(
        frames: List<ByteArray>,
        delayMs: Long,
        numPlays: Int = 0,
    ): ByteArray {
        val binary =
            EncoderBinaryLocator.findWebpBinary()
                ?: throw AnimEncoderException(
                    "No animated-WebP encoder found on PATH. " +
                        "Install libwebp (provides img2webp) via 'brew install webp' on macOS, " +
                        "'apt-get install webp' on Debian/Ubuntu, or ensure ffmpeg is on PATH as a fallback.",
                )

        val tempDir = createTempDir()
        return try {
            val outputFile = File(tempDir, "output.webp")
            encodeWithBinary(binary, frames, delayMs, numPlays, tempDir, outputFile)
            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw AnimEncoderException(
                    "WebP encoder '$binary' ran successfully but produced an empty output file.",
                )
            }
            outputFile.readBytes()
        } finally {
            // deleteRecursively() returns false if any file could not be deleted (e.g. on
            // Windows where a subprocess may still hold file handles after exit).  We log a
            // warning rather than throwing so the caller still receives the encoded bytes.
            val deleted = tempDir.deleteRecursively()
            if (!deleted) {
                log.warning(
                    "WebpEncoder: failed to fully clean up temp directory '${tempDir.absolutePath}'. " +
                        "Temp files may remain (common on Windows when the encoder subprocess " +
                        "releases file handles asynchronously).",
                )
            }
        }
    }

    private fun encodeWithBinary(
        binary: String,
        frames: List<ByteArray>,
        delayMs: Long,
        numPlays: Int,
        tempDir: File,
        outputFile: File,
    ) {
        // Write frames to temp dir
        val frameFiles =
            frames.mapIndexed { i, png ->
                val f = File(tempDir, "frame%05d.png".format(i))
                f.writeBytes(png)
                f
            }

        val cmd =
            if (binary == "img2webp") {
                buildImg2WebpCommand(frameFiles, delayMs, numPlays, outputFile)
            } else {
                buildFfmpegCommand(frameFiles, delayMs, outputFile, tempDir)
            }

        val process =
            ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()

        // Drain stdout concurrently to prevent pipe-buffer deadlock (OS buffer ~64 KiB on Linux).
        // ffmpeg / img2webp may write progress info to stdout; if the buffer fills before
        // waitFor() drains it, the subprocess blocks and waitFor() never returns.
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
                "WebP encoder '$binary' timed out after ${TIMEOUT_SECONDS}s.",
            )
        }

        if (process.exitValue() != 0) {
            val stderr = String(stderrBytes, Charsets.UTF_8).take(2048)
            // Provide a targeted hint when ffmpeg is present but lacks libwebp_anim support
            // (e.g. the stripped macOS system ffmpeg or a minimal CI build).
            val hint =
                if (binary == "ffmpeg" && "libwebp_anim" in stderr) {
                    " The ffmpeg binary found on PATH does not include libwebp_anim support. " +
                        "Install a full-featured ffmpeg build (e.g. 'brew install ffmpeg' on macOS, " +
                        "'apt-get install ffmpeg' on Debian/Ubuntu) or install libwebp ('brew install webp') " +
                        "to use img2webp instead."
                } else {
                    ""
                }
            throw AnimEncoderException(
                "WebP encoder '$binary' exited with code ${process.exitValue()}.$hint Stderr: $stderr",
            )
        }
    }

    private fun buildImg2WebpCommand(
        frameFiles: List<File>,
        delayMs: Long,
        numPlays: Int,
        outputFile: File,
    ): List<String> {
        val cmd = mutableListOf("img2webp", "-loop", numPlays.toString())
        for (f in frameFiles) {
            cmd += listOf("-d", delayMs.toString(), f.absolutePath)
        }
        cmd += listOf("-o", outputFile.absolutePath)
        return cmd
    }

    private fun buildFfmpegCommand(
        frameFiles: List<File>,
        delayMs: Long,
        outputFile: File,
        tempDir: File,
    ): List<String> {
        // For ffmpeg we need a pattern — files are already named frame%05d.png
        val fps = (1000.0 / delayMs).coerceAtLeast(1.0)
        val pattern = File(tempDir, "frame%05d.png").absolutePath
        // Use `-c:v libwebp_anim` explicitly so ffmpeg does not have to infer the
        // codec from the output file extension.  Without this, a ffmpeg build whose
        // extension-detection is ambiguous (e.g. libwebp vs. vp8 muxed into WebP
        // container) may silently produce a non-animated or non-WebP file.
        // `-loop 0` is placed after the codec to ensure it is interpreted as a
        // per-stream WebP loop count (0 = infinite) rather than an input option.
        return listOf(
            "ffmpeg",
            "-y",
            "-framerate",
            "%.3f".format(fps),
            "-i",
            pattern,
            "-c:v",
            "libwebp_anim",
            "-loop",
            "0",
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
            // Non-POSIX filesystem (e.g. Windows): fall back to a plain temp directory.
            // Files.createTempDirectory() can throw IOException (disk full, temp dir
            // unwritable, etc.) — wrap it so callers always see AnimEncoderException
            // rather than a raw IOException that bypasses the RenderCommand handler.
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
