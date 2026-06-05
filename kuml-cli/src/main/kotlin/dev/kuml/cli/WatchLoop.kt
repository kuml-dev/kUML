package dev.kuml.cli

import java.io.File

/**
 * Polls a file for modifications and invokes a callback on each detected change.
 *
 * Uses [File.lastModified] polled every [pollIntervalMs] milliseconds.
 * Blocks until the current thread is interrupted (e.g. via Ctrl+C / SIGINT).
 *
 * Note: [java.nio.file.WatchService] is deliberately **not** used here because
 * its macOS implementation falls back to polling with up to ~10 s latency,
 * which is unacceptable for a live-reload UX.
 */
internal object WatchLoop {
    /**
     * Starts the poll loop for [file].
     *
     * @param file            The file to watch for modifications.
     * @param pollIntervalMs  Poll interval in milliseconds. Default: 500.
     * @param onChanged       Callback invoked synchronously in the poll thread whenever
     *                        the file's last-modified timestamp changes.
     */
    internal fun watch(
        file: File,
        pollIntervalMs: Long = 500L,
        onChanged: () -> Unit,
    ) {
        var lastModified = file.lastModified()
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(pollIntervalMs)
                val current = file.lastModified()
                if (current != lastModified) {
                    lastModified = current
                    onChanged()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt() // restore interrupted status for caller
        }
    }
}
