package dev.kuml.ai.internal

import java.time.Duration
import java.util.concurrent.TimeUnit

/** Small ProcessBuilder helper with timeout and stdin-passing. Internal — not part of public API. */
internal object ShellOut {
    internal data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /**
     * Runs [command]; pipes [stdin] (if non-null) to the process stdin;
     * enforces [timeout] (default 5 seconds).
     *
     * @throws RuntimeException on process start failure or timeout
     */
    internal fun run(
        command: List<String>,
        stdin: String? = null,
        timeout: Duration = Duration.ofSeconds(5),
    ): Result {
        val pb =
            ProcessBuilder(command)
                .redirectErrorStream(false)

        val process = pb.start()

        // Write stdin if provided
        if (stdin != null) {
            process.outputStream.use { os ->
                os.write(stdin.toByteArray(Charsets.UTF_8))
                os.flush()
            }
        } else {
            process.outputStream.close()
        }

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()

        val finished = process.waitFor(timeout.seconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException(
                "Command timed out after ${timeout.seconds}s: ${command.joinToString(" ")}",
            )
        }

        return Result(process.exitValue(), stdout.trim(), stderr.trim())
    }
}
