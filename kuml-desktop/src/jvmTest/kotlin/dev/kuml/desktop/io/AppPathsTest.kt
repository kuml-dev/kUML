package dev.kuml.desktop.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppPathsTest : FunSpec({

    test("macOS resolves to Library/Application Support/kUML") {
        val path = AppPaths.resolveBaseDir(
            os = "mac os x",
            env = emptyMap(),
            userHome = "/Users/testuser",
        )
        path.toString() shouldBe "/Users/testuser/Library/Application Support/kUML"
    }

    test("Windows resolves to APPDATA/kUML") {
        val appData = "C:\\Users\\testuser\\AppData\\Roaming"
        val path = AppPaths.resolveBaseDir(
            os = "windows 11",
            env = mapOf("APPDATA" to appData),
            userHome = "C:\\Users\\testuser",
        )
        // On non-Windows hosts Paths.get uses the host separator, so we check the string-based join
        path.toString() shouldBe java.nio.file.Paths.get(appData, "kUML").toString()
    }

    test("Linux with XDG_CONFIG_HOME resolves to XDG_CONFIG_HOME/kuml") {
        val path = AppPaths.resolveBaseDir(
            os = "linux",
            env = mapOf("XDG_CONFIG_HOME" to "/home/testuser/.config-custom"),
            userHome = "/home/testuser",
        )
        path.toString() shouldBe "/home/testuser/.config-custom/kuml"
    }

    test("Linux without XDG_CONFIG_HOME falls back to ~/.config/kuml") {
        val path = AppPaths.resolveBaseDir(
            os = "linux",
            env = emptyMap(),
            userHome = "/home/testuser",
        )
        path.toString() shouldBe "/home/testuser/.config/kuml"
    }
})
