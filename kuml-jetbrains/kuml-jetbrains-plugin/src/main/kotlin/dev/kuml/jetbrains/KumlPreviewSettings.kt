package dev.kuml.jetbrains

import com.intellij.ide.util.PropertiesComponent

/**
 * Persisted, application-level settings for the kUML live preview.
 *
 * Backed by [PropertiesComponent] to avoid heavyweight
 * `PersistentStateComponent` boilerplate for a single string value.
 */
internal object KumlPreviewSettings {
    const val CLI_PATH_KEY: String = "dev.kuml.jetbrains.cliPath"
    const val THEME_KEY: String = "dev.kuml.jetbrains.theme"
    const val DEFAULT_THEME: String = "kuml"
    val THEMES: List<String> = listOf("kuml", "plain", "elegant", "playful")

    /** Returns the user-configured `kuml` CLI path, or `null` if unset. */
    fun cliPath(): String? =
        try {
            PropertiesComponent.getInstance().getValue(CLI_PATH_KEY)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            // No application (e.g. unit tests without IDE) — treat as unset.
            null
        }

    fun setCliPath(path: String?) {
        try {
            val pc = PropertiesComponent.getInstance()
            if (path.isNullOrBlank()) pc.unsetValue(CLI_PATH_KEY) else pc.setValue(CLI_PATH_KEY, path)
        } catch (_: Throwable) {
            // Ignore in non-IDE contexts.
        }
    }

    /** Returns the stored theme if valid, else [DEFAULT_THEME]. */
    fun theme(): String =
        try {
            val stored = PropertiesComponent.getInstance().getValue(THEME_KEY)
            if (stored != null && stored in THEMES) stored else DEFAULT_THEME
        } catch (_: Throwable) {
            DEFAULT_THEME
        }

    fun setTheme(theme: String?) {
        try {
            val pc = PropertiesComponent.getInstance()
            if (theme == null || theme !in THEMES) pc.unsetValue(THEME_KEY) else pc.setValue(THEME_KEY, theme)
        } catch (_: Throwable) {
            // Ignore in non-IDE contexts.
        }
    }
}
