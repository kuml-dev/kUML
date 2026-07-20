package dev.kuml.desktop.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Unit tests for the pure helper functions extracted from [FileMenu] during the
 * design-review pass (P1 — unsaved-changes guard; P3 — export filename derivation).
 * No Compose/Swing/AWT harness needed — both functions are plain, side-effect-free.
 */
class FileMenuTest :
    FunSpec({

        // ── shouldProceedAfterUnsavedChoice() — P1 ─────────────────────────────

        test("DISCARD always proceeds, regardless of saveSucceeded") {
            FileMenu.shouldProceedAfterUnsavedChoice(UnsavedChoice.DISCARD, saveSucceeded = false) shouldBe true
            FileMenu.shouldProceedAfterUnsavedChoice(UnsavedChoice.DISCARD, saveSucceeded = true) shouldBe true
        }

        test("CANCEL never proceeds, regardless of saveSucceeded") {
            FileMenu.shouldProceedAfterUnsavedChoice(UnsavedChoice.CANCEL, saveSucceeded = false) shouldBe false
            FileMenu.shouldProceedAfterUnsavedChoice(UnsavedChoice.CANCEL, saveSucceeded = true) shouldBe false
        }

        test("SAVE proceeds only if the save actually succeeded") {
            FileMenu.shouldProceedAfterUnsavedChoice(UnsavedChoice.SAVE, saveSucceeded = true) shouldBe true
            FileMenu.shouldProceedAfterUnsavedChoice(UnsavedChoice.SAVE, saveSucceeded = false) shouldBe false
        }

        // ── exportBaseName() — P3 ──────────────────────────────────────────────

        test("exportBaseName() strips the full .kuml.kts suffix, not just .kts") {
            FileMenu.exportBaseName(File("/tmp/diagram.kuml.kts")) shouldBe "diagram"
        }

        test("exportBaseName() falls back to nameWithoutExtension for other extensions") {
            FileMenu.exportBaseName(File("/tmp/script.kts")) shouldBe "script"
        }

        test("exportBaseName() falls back to 'diagram' when no file is open") {
            FileMenu.exportBaseName(null) shouldBe "diagram"
        }

        test("exportBaseName() preserves dots that are part of the base name itself") {
            FileMenu.exportBaseName(File("/tmp/my.model.v2.kuml.kts")) shouldBe "my.model.v2"
        }
    })
