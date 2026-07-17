package dev.kuml.desktop.io

import java.io.File

object RecentFiles {
    fun add(
        list: List<String>,
        path: String,
        max: Int = AppSettings.MAX_RECENT_FILES,
    ): List<String> = (listOf(path) + list.filterNot { it == path }).take(max)

    fun pruneMissing(list: List<String>): List<String> = list.filter { File(it).exists() }

    fun remove(
        list: List<String>,
        path: String,
    ): List<String> = list.filterNot { it == path }
}
