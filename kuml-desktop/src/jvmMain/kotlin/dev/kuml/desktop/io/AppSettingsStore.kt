package dev.kuml.desktop.io

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class AppSettingsStore(val file: Path = AppPaths.settingsFile()) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): AppSettings = try {
        if (!Files.exists(file)) AppSettings.DEFAULT
        else json.decodeFromString<AppSettings>(Files.readString(file))
    } catch (_: Exception) {
        AppSettings.DEFAULT
    }

    fun save(settings: AppSettings) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(settings))
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
