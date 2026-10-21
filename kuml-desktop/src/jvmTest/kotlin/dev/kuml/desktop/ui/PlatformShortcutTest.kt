package dev.kuml.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlatformShortcutTest :
    FunSpec({

        test("macOS uses the command key (meta), not ctrl") {
            platformShortcut(key = Key.S, os = "Mac OS X") shouldBe KeyShortcut(key = Key.S, meta = true)
        }

        test("Windows and Linux use ctrl") {
            platformShortcut(key = Key.S, os = "Windows 11") shouldBe KeyShortcut(key = Key.S, ctrl = true)
            platformShortcut(key = Key.S, os = "Linux") shouldBe KeyShortcut(key = Key.S, ctrl = true)
        }

        test("shift and alt are carried over on every platform") {
            platformShortcut(key = Key.Z, shift = true, os = "Mac OS X") shouldBe KeyShortcut(key = Key.Z, meta = true, shift = true)
            platformShortcut(key = Key.Z, shift = true, os = "Linux") shouldBe KeyShortcut(key = Key.Z, ctrl = true, shift = true)
            platformShortcut(key = Key.One, alt = true, os = "Windows 10") shouldBe KeyShortcut(key = Key.One, ctrl = true, alt = true)
        }

        test("platform detection and label") {
            isMacOs("Mac OS X") shouldBe true
            isMacOs("Darwin") shouldBe true
            isMacOs("Windows 11") shouldBe false
            isMacOs("Linux") shouldBe false
            commandKeyLabel("Mac OS X") shouldBe "⌘"
            commandKeyLabel("Linux") shouldBe "Ctrl"
        }
    })
