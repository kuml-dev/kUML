package dev.kuml.desktop.ai.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure-function tests for the Enter-to-send / Shift+Enter-newline decision logic extracted from
 * [InputPane] -- kuml-desktop has no Compose UI test harness (see the cross-cutting note in the
 * V3.7.4 plan), so the actual key-handling decision is factored out into [shouldSubmitOnKey] /
 * [shouldConsumeKey] specifically to be testable here.
 */
class InputPaneTest :
    FunSpec({

        // ── shouldSubmitOnKey ────────────────────────────────────────────────────────────

        test("Enter KeyDown without shift, not running -> submit") {
            shouldSubmitOnKey(type = KeyEventType.KeyDown, key = Key.Enter, isShiftPressed = false, isRunning = false) shouldBe true
        }

        test("NumPadEnter KeyDown without shift, not running -> submit") {
            shouldSubmitOnKey(
                type = KeyEventType.KeyDown,
                key = Key.NumPadEnter,
                isShiftPressed = false,
                isRunning = false,
            ) shouldBe true
        }

        test("Shift+Enter never submits") {
            shouldSubmitOnKey(type = KeyEventType.KeyDown, key = Key.Enter, isShiftPressed = true, isRunning = false) shouldBe false
        }

        test("Enter KeyUp never submits (only KeyDown submits)") {
            shouldSubmitOnKey(type = KeyEventType.KeyUp, key = Key.Enter, isShiftPressed = false, isRunning = false) shouldBe false
        }

        test("Enter while a response is running never submits (no double-send)") {
            shouldSubmitOnKey(type = KeyEventType.KeyDown, key = Key.Enter, isShiftPressed = false, isRunning = true) shouldBe false
        }

        test("Any other key never submits") {
            shouldSubmitOnKey(type = KeyEventType.KeyDown, key = Key.A, isShiftPressed = false, isRunning = false) shouldBe false
            shouldSubmitOnKey(type = KeyEventType.KeyDown, key = Key.Tab, isShiftPressed = false, isRunning = false) shouldBe false
        }

        // ── shouldConsumeKey ─────────────────────────────────────────────────────────────

        test("unmodified Enter is consumed on both KeyDown and KeyUp") {
            shouldConsumeKey(type = KeyEventType.KeyDown, key = Key.Enter, isShiftPressed = false, isRunning = false) shouldBe true
            shouldConsumeKey(type = KeyEventType.KeyUp, key = Key.Enter, isShiftPressed = false, isRunning = false) shouldBe true
        }

        test("unmodified Enter is consumed even while running (submit is suppressed, newline still must not appear)") {
            shouldConsumeKey(type = KeyEventType.KeyDown, key = Key.Enter, isShiftPressed = false, isRunning = true) shouldBe true
        }

        test("Shift+Enter is never consumed -- the newline must go through") {
            shouldConsumeKey(type = KeyEventType.KeyDown, key = Key.Enter, isShiftPressed = true, isRunning = false) shouldBe false
            shouldConsumeKey(type = KeyEventType.KeyUp, key = Key.Enter, isShiftPressed = true, isRunning = false) shouldBe false
        }

        test("other keys are never consumed") {
            shouldConsumeKey(type = KeyEventType.KeyDown, key = Key.A, isShiftPressed = false, isRunning = false) shouldBe false
        }
    })
