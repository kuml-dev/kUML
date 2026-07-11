package dev.kuml.desktop.workspace

import java.io.File
import java.nio.file.Files

/**
 * Lightweight recursive scan for `*.kuml.kts` files under an Engineering-mode
 * workspace root (V3.6.4).
 *
 * [dev.kuml.workspace.WorkspaceScanner] only indexes Markdown documents (Knowledge
 * mode); the Desktop Engineering-mode tree needs the script files themselves. Reuses
 * the same hidden-directory/symlink-skip and file-count DoS-guard discipline as
 * `WorkspaceScanner` rather than doing an unbounded walk.
 */
object EngineeringFileScanner {
    private const val MAX_SCRIPT_FILE_COUNT = 20_000

    fun scan(root: File): List<File> {
        val files =
            root
                .walkTopDown()
                .onEnter { dir -> !dir.name.startsWith(".") && !Files.isSymbolicLink(dir.toPath()) }
                .filter { it.isFile && it.name.endsWith(".kuml.kts") }
                .toList()
        require(files.size <= MAX_SCRIPT_FILE_COUNT) {
            "Workspace scan aborted: $root contains ${files.size} kUML scripts, " +
                "exceeding the safety cap of $MAX_SCRIPT_FILE_COUNT."
        }
        return files.sortedBy { it.relativeTo(root).path }
    }
}
