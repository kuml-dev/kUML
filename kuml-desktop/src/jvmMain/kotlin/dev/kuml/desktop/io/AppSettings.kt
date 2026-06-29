package dev.kuml.desktop.io

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val schemaVersion: Int = 1,
    val theme: String = "kuml",
    val language: String = "en",
    val recentFiles: List<String> = emptyList(),
    val lastDir: String? = null,
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800,
    val windowX: Int = -1,
    val windowY: Int = -1,
    // V3.0.24 — AI panel state
    val aiPanelOpen: Boolean = false,
    val aiPanelWidthPx: Int = 420,
) {
    companion object {
        val DEFAULT = AppSettings()
        const val MAX_RECENT_FILES = 10
    }
}
