package dev.kuml.desktop.io

import java.io.File

/** Pure helpers for the remembered open/export dialog start directories. */
object DialogDirs {
    /** Stored directory if it still exists, otherwise [userHome]. Never null. */
    fun resolve(
        stored: String?,
        userHome: File = File(System.getProperty("user.home")),
    ): File {
        if (stored.isNullOrBlank()) return userHome
        val dir = File(stored)
        return if (dir.isDirectory) dir else userHome
    }

    /** Directory containing [file], or null. */
    fun dirOfFile(file: File): String? = file.parentFile?.absolutePath

    /** The chosen directory itself (export targets: reuse the folder just used). */
    fun dirOfDirectoryChoice(dir: File): String = dir.absolutePath

    /** Parent of the chosen directory, or the directory itself at a filesystem root. */
    fun parentOfDirectoryChoice(dir: File): String = dir.parentFile?.absolutePath ?: dir.absolutePath
}
