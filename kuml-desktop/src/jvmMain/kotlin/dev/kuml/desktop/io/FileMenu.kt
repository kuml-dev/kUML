package dev.kuml.desktop.io

import dev.kuml.desktop.i18n.Strings
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

enum class UnsavedChoice { SAVE, DISCARD, CANCEL }

object FileMenu {
    fun chooseOpen(parent: java.awt.Window?, initialDir: File?, strings: Strings): File? =
        runOnEdtBlocking {
            val chooser = JFileChooser(initialDir).apply {
                dialogTitle = strings.dialogOpenTitle
                fileFilter = KumlFileFilter
                isAcceptAllFileFilterUsed = true
            }
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }

    /** V3.6.4 — directory picker for File → Open Workspace…. */
    fun chooseOpenDirectory(parent: java.awt.Window?, initialDir: File?, strings: Strings): File? =
        runOnEdtBlocking {
            val chooser = JFileChooser(initialDir).apply {
                dialogTitle = strings.dialogOpenWorkspaceTitle
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            }
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }

    fun chooseSave(parent: java.awt.Window?, initialDir: File?, suggestedName: String, strings: Strings): File? =
        runOnEdtBlocking {
            val chooser = JFileChooser(initialDir).apply {
                dialogTitle = strings.dialogSaveTitle
                fileFilter = KumlFileFilter
                isAcceptAllFileFilterUsed = true
                if (suggestedName.isNotBlank()) {
                    selectedFile = File(initialDir ?: File(System.getProperty("user.home")), suggestedName)
                }
            }
            if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }

    fun confirmUnsaved(parent: java.awt.Window?, strings: Strings): UnsavedChoice {
        val options = arrayOf(strings.dialogUnsavedSave, strings.dialogUnsavedDiscard, strings.dialogUnsavedCancel)
        val result = runOnEdtBlocking {
            JOptionPane.showOptionDialog(
                parent, strings.dialogUnsavedMessage, strings.dialogUnsavedTitle,
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0],
            )
        }
        return when (result) {
            0 -> UnsavedChoice.SAVE
            1 -> UnsavedChoice.DISCARD
            else -> UnsavedChoice.CANCEL
        }
    }

    fun readScript(file: File): String = file.readText(Charsets.UTF_8)

    fun writeScript(file: File, content: String) {
        val tmp = File(file.parentFile ?: File(System.getProperty("user.home")), "${file.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }
}

private fun <T> runOnEdtBlocking(block: () -> T): T =
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        // Capture through a typed single-element list so no unchecked cast from
        // a nullable holder (T?) back to T is needed — the list element is T.
        val holder = mutableListOf<T>()
        SwingUtilities.invokeAndWait { holder.add(block()) }
        holder.single()
    }
