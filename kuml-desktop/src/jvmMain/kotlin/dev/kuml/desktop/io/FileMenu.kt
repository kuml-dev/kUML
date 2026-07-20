package dev.kuml.desktop.io

import dev.kuml.desktop.i18n.Strings
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

enum class UnsavedChoice { SAVE, DISCARD, CANCEL }

object FileMenu {
    fun chooseOpen(
        parent: java.awt.Window?,
        initialDir: File?,
        strings: Strings,
    ): File? =
        runOnEdtBlocking {
            val chooser =
                JFileChooser(initialDir).apply {
                    dialogTitle = strings.dialogOpenTitle
                    fileFilter = KumlFileFilter
                    isAcceptAllFileFilterUsed = true
                }
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }

    /** V3.6.4 — directory picker for File → Open Workspace…. */
    fun chooseOpenDirectory(
        parent: java.awt.Window?,
        initialDir: File?,
        strings: Strings,
    ): File? =
        runOnEdtBlocking {
            val chooser =
                JFileChooser(initialDir).apply {
                    dialogTitle = strings.dialogOpenWorkspaceTitle
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                }
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }

    fun chooseSave(
        parent: java.awt.Window?,
        initialDir: File?,
        suggestedName: String,
        strings: Strings,
    ): File? =
        runOnEdtBlocking {
            val chooser =
                JFileChooser(initialDir).apply {
                    dialogTitle = strings.dialogSaveTitle
                    fileFilter = KumlFileFilter
                    isAcceptAllFileFilterUsed = true
                    if (suggestedName.isNotBlank()) {
                        selectedFile = File(initialDir ?: File(System.getProperty("user.home")), suggestedName)
                    }
                }
            if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }

    fun confirmUnsaved(
        parent: java.awt.Window?,
        strings: Strings,
    ): UnsavedChoice {
        val options = arrayOf(strings.dialogUnsavedSave, strings.dialogUnsavedDiscard, strings.dialogUnsavedCancel)
        val result =
            runOnEdtBlocking {
                JOptionPane.showOptionDialog(
                    parent,
                    strings.dialogUnsavedMessage,
                    strings.dialogUnsavedTitle,
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[0],
                )
            }
        return when (result) {
            0 -> UnsavedChoice.SAVE
            1 -> UnsavedChoice.DISCARD
            else -> UnsavedChoice.CANCEL
        }
    }

    fun readScript(file: File): String = file.readText(Charsets.UTF_8)

    fun writeScript(
        file: File,
        content: String,
    ) {
        val tmp = File(file.parentFile ?: File(System.getProperty("user.home")), "${file.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    /** Atomic binary write (P3, design review) — used by PNG export. Mirrors [writeScript]'s tmp+rename pattern. */
    fun writeBytes(
        file: File,
        bytes: ByteArray,
    ) {
        val tmp = File(file.parentFile ?: File(System.getProperty("user.home")), "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }

    /**
     * File chooser for exporting the rendered diagram (P3, design review) — separate from
     * [chooseSave] because export targets a different extension (svg/png) than the
     * kUML-script [KumlFileFilter].
     */
    fun chooseExport(
        parent: java.awt.Window?,
        initialDir: File?,
        suggestedName: String,
        description: String,
        extension: String,
        strings: Strings,
    ): File? =
        runOnEdtBlocking {
            val chooser =
                JFileChooser(initialDir).apply {
                    dialogTitle = strings.dialogExportTitle
                    fileFilter = FileNameExtensionFilter(description, extension)
                    isAcceptAllFileFilterUsed = true
                    if (suggestedName.isNotBlank()) {
                        selectedFile = File(initialDir ?: File(System.getProperty("user.home")), suggestedName)
                    }
                }
            if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
                return@runOnEdtBlocking null
            }
            ensureExtension(chooser.selectedFile, extension)
        }

    /** Appends `.$extension` to [file] if it isn't already present (case-insensitive). */
    private fun ensureExtension(
        file: File,
        extension: String,
    ): File =
        if (file.name.endsWith(".$extension", ignoreCase = true)) {
            file
        } else {
            File(file.parentFile, "${file.name}.$extension")
        }

    /**
     * Suggested export base filename (without extension) derived from the current script
     * file, e.g. `diagram.kuml.kts` → `diagram`. Falls back to `"diagram"` when no file is
     * open yet. Extracted as a pure function for headless unit testing (P3, design review).
     */
    fun exportBaseName(file: File?): String {
        val name = file?.name ?: return "diagram"
        return if (name.endsWith(".kuml.kts")) name.removeSuffix(".kuml.kts") else file.nameWithoutExtension
    }

    /**
     * Pure decision logic extracted from [dev.kuml.desktop.MainWindow]'s unsaved-changes
     * guard (`confirmUnsavedAndThen`), so it is unit-testable without a Swing/AWT
     * event-dispatch-thread harness (P1, design review). Given the user's [choice] from
     * [confirmUnsaved] and whether a save attempt (only relevant for [UnsavedChoice.SAVE])
     * succeeded, decides whether the originally-requested action should proceed.
     */
    fun shouldProceedAfterUnsavedChoice(
        choice: UnsavedChoice,
        saveSucceeded: Boolean,
    ): Boolean =
        when (choice) {
            UnsavedChoice.DISCARD -> true
            UnsavedChoice.SAVE -> saveSucceeded
            UnsavedChoice.CANCEL -> false
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
