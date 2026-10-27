package dev.kuml.desktop.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Cheap change detection for an open workspace directory: a fingerprint over the path,
 * modification time and size of every `*.md` and `*.kuml.kts` file below the root.
 *
 * Polling a fingerprint instead of registering a `WatchService` is deliberate: the JDK's
 * macOS implementation polls anyway (with a ~10 s delay), recursive registration needs
 * bookkeeping for directories created later, and editors that save via rename would need
 * extra handling. A stat sweep over a few hundred files every couple of seconds behaves
 * identically on macOS, Linux and Windows. The walk follows the same discipline as
 * [dev.kuml.workspace.WorkspaceScanner]: hidden directories and symbolic links are skipped,
 * and the file count is capped.
 */
object WorkspaceChangeDetector {
    private const val MAX_FILES = 20_000
    const val DEFAULT_POLL_INTERVAL_MS: Long = 2_000L

    /** Returns a value that differs whenever a tracked file was added, removed, touched or resized. */
    fun fingerprint(root: File): Long {
        var hash = 1125899906842597L
        var count = 0
        root
            .walkTopDown()
            .onEnter { dir -> !dir.name.startsWith(".") && !Files.isSymbolicLink(dir.toPath()) }
            .filter { it.isFile && (it.extension == "md" || it.name.endsWith(".kuml.kts")) }
            .takeWhile { ++count <= MAX_FILES }
            .forEach { file ->
                hash = 31 * hash + file.relativeTo(root).path.hashCode()
                hash = 31 * hash + file.lastModified()
                hash = 31 * hash + file.length()
            }
        // Order-dependence is fine: walkTopDown order is stable for an unchanged tree, and a
        // reorder only ever follows an add/remove that changes the hash anyway.
        return hash
    }
}

/**
 * Polls [root] and calls [onChange] after the workspace contents changed on disk. The first
 * sweep only records the baseline, so opening a workspace never triggers a refresh by itself.
 */
@Composable
fun WorkspaceChangeEffect(
    root: File,
    intervalMs: Long = WorkspaceChangeDetector.DEFAULT_POLL_INTERVAL_MS,
    onChange: suspend () -> Unit,
) {
    val latestOnChange = rememberUpdatedState(onChange)
    LaunchedEffect(root, intervalMs) {
        suspend fun sweep(): Long? = withContext(Dispatchers.IO) { runCatching { WorkspaceChangeDetector.fingerprint(root) }.getOrNull() }

        var last = sweep()
        while (isActive) {
            delay(intervalMs)
            val current = sweep() ?: continue
            if (current != last) {
                last = current
                latestOnChange.value()
            }
        }
    }
}
