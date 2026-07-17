package dev.kuml.desktop.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object AppPaths {
    fun settingsDir(): Path = resolveBaseDir().also { Files.createDirectories(it) }

    fun settingsFile(): Path = settingsDir().resolve("desktop-settings.json")

    internal fun resolveBaseDir(
        os: String = System.getProperty("os.name", "").lowercase(),
        env: Map<String, String?> = System.getenv(),
        userHome: String = System.getProperty("user.home", ""),
    ): Path =
        when {
            "mac" in os -> Paths.get(userHome, "Library", "Application Support", "kUML")
            "win" in os -> Paths.get(env["APPDATA"] ?: Paths.get(userHome, "AppData", "Roaming").toString(), "kUML")
            else -> Paths.get(env["XDG_CONFIG_HOME"] ?: Paths.get(userHome, ".config").toString(), "kuml")
        }
}
