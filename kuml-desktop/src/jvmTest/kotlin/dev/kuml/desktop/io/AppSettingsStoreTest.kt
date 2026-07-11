package dev.kuml.desktop.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class AppSettingsStoreTest : FunSpec({

    test("load() when file does not exist returns DEFAULT") {
        val tempDir = Files.createTempDirectory("kuml-store-test")
        val store = AppSettingsStore(tempDir.resolve("nonexistent.json"))
        try {
            store.load() shouldBe AppSettings.DEFAULT
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("save() + load() round-trip preserves settings") {
        val tempDir = Files.createTempDirectory("kuml-store-test")
        val store = AppSettingsStore(tempDir.resolve("settings.json"))
        val original = AppSettings(
            theme = "dark",
            language = "de",
            recentFiles = listOf("/tmp/diagram.kuml.kts"),
            lastDir = "/tmp",
            windowWidth = 1600,
            windowHeight = 1000,
            windowX = 50,
            windowY = 75,
        )
        try {
            store.save(original)
            store.load() shouldBe original
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("load() with corrupt JSON returns DEFAULT") {
        val tempDir = Files.createTempDirectory("kuml-store-test")
        val settingsFile = tempDir.resolve("settings.json")
        Files.writeString(settingsFile, "{ this is not valid json !!!}")
        val store = AppSettingsStore(settingsFile)
        try {
            store.load() shouldBe AppSettings.DEFAULT
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("save() creates parent directory if it does not exist") {
        val tempDir = Files.createTempDirectory("kuml-store-test")
        val nestedFile = tempDir.resolve("nested/subdir/settings.json")
        val store = AppSettingsStore(nestedFile)
        try {
            store.save(AppSettings.DEFAULT)
            Files.exists(nestedFile) shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("save() + load() round-trip preserves trustedWorkspaces (V3.6.4)") {
        val tempDir = Files.createTempDirectory("kuml-store-test")
        val store = AppSettingsStore(tempDir.resolve("settings.json"))
        val original = AppSettings.DEFAULT.copy(
            trustedWorkspaces = listOf("/home/user/workspace-a", "/home/user/workspace-b"),
        )
        try {
            store.save(original)
            store.load() shouldBe original
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
})
