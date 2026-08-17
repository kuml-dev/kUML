package dev.kuml.jetbrains.asciidoc

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Watches resolved `kuml::` macro target files and triggers a preview refresh
 * when any of them change on disk.
 *
 * Subscribe via [attach]; automatically unsubscribes when [parentDisposable] is disposed.
 */
internal class KumlAsciidocReferencedFileWatcher(
    project: Project,
    parentDisposable: Disposable,
    private val onReferencedFileChanged: () -> Unit,
) : BulkFileListener,
    Disposable {
    private val watchedPaths = AtomicReference<Set<String>>(emptySet())
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    private val connection = project.messageBus.connect(parentDisposable)

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, this)
    }

    /** Replaces the set of absolute macro target paths currently watched. */
    fun updateWatchedPaths(paths: Set<Path>) {
        watchedPaths.set(paths.map { it.toAbsolutePath().normalize().toString() }.toSet())
    }

    override fun after(events: MutableList<out VFileEvent>) {
        val watched = watchedPaths.get()
        if (watched.isEmpty()) return
        val hit =
            events.any { event ->
                val path = event.path
                watched.any { watchedPath -> path == watchedPath || path.startsWith("$watchedPath/") }
            }
        if (hit) {
            // Coalesce rapid successive events into a single refresh.
            alarm.cancelAllRequests()
            alarm.addRequest({ onReferencedFileChanged() }, DEBOUNCE_MS)
        }
    }

    override fun dispose() {
        connection.disconnect()
        alarm.cancelAllRequests()
        watchedPaths.set(emptySet())
    }

    companion object {
        private const val DEBOUNCE_MS: Int = 150
    }
}
