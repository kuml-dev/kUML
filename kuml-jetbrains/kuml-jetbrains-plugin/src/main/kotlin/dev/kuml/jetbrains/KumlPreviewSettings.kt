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
}
