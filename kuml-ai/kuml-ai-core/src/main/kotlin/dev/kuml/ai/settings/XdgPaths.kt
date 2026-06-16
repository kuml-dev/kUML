package dev.kuml.ai.settings

import dev.kuml.ai.vault.OsDetection
import java.nio.file.Path
import java.nio.file.Paths

/** Resolves kUML's per-user config directory in an OS-aware way. */
public object XdgPaths {
    /**
     * Returns the directory housing settings and the secrets fallback file.
     *
     * Paths per OS:
     *  - macOS  → ~/Library/Application Support/kuml/
     *  - Linux  → $XDG_CONFIG_HOME/kuml/  (default: ~/.config/kuml/)
     *  - Windows → %APPDATA%\kuml\
     *  - Other  → ~/.kuml/
     *
     * Override for tests: set system property `kuml.config.home` to any path.
     */
    public fun kumlConfigDir(): Path = resolveBaseDir().resolve("kuml")

    /** Path to the AI settings JSON file. */
    public fun aiSettingsPath(): Path = kumlConfigDir().resolve("ai-settings.json")

    /** Path to the plain-text secrets fallback file. */
    public fun plainSecretsPath(): Path = kumlConfigDir().resolve("secrets.json")

    /**
     * Internal helper: tests inject a custom base directory via the
     * `kuml.config.home` system property.
     */
    internal fun resolveBaseDir(): Path {
        // Test override — highest priority
        System.getProperty("kuml.config.home")?.let { override ->
            return Paths.get(override)
        }

        val home = Paths.get(System.getProperty("user.home"))

        return when (OsDetection.current()) {
            OsDetection.Os.MAC -> {
                // Honour XDG_CONFIG_HOME if set (rare on macOS but respects user preference)
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")
                if (!xdgConfig.isNullOrBlank()) {
                    Paths.get(xdgConfig)
                } else {
                    home.resolve("Library").resolve("Application Support")
                }
            }

            OsDetection.Os.LINUX -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")
                if (!xdgConfig.isNullOrBlank()) {
                    Paths.get(xdgConfig)
                } else {
                    home.resolve(".config")
                }
            }

            OsDetection.Os.WINDOWS -> {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrBlank()) {
                    Paths.get(appData)
                } else {
                    home.resolve("AppData").resolve("Roaming")
                }
            }

            OsDetection.Os.OTHER -> home.resolve(".kuml-parent")
        }
    }
}
