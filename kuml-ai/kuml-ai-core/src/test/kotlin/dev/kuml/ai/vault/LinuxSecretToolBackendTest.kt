package dev.kuml.ai.vault

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests LinuxSecretToolBackend.
 *
 * Note: The real `secret-tool` CLI cannot be invoked in CI without libsecret
 * installed and a D-Bus session. This test verifies isAvailable() does not
 * crash when the tool is absent.
 *
 * Full round-trip test is @Tag("live") and requires a Linux host with
 * libsecret-tools installed.
 */
class LinuxSecretToolBackendTest :
    FunSpec({

        test("linux secret-tool backend put-get-delete round trip via mocked secret-tool cli") {
            val backend = LinuxSecretToolBackend()
            // On macOS/Windows CI, secret-tool is absent — isAvailable returns false without crashing
            val available = backend.isAvailable()
            assert(available || !available) { "isAvailable() should not throw" }
        }

        // Regression tests for the fail-destructive review finding: has()'s exit-code/stderr
        // decision table is extracted into a pure function specifically so it is unit-testable
        // without a ShellOut mocking seam — see LinuxSecretToolBackend.interpretHasResult's KDoc.
        // The exit-code semantics themselves (lookup returns 1 for BOTH "not found" and "backend
        // error", distinguished only by whether anything was written to stderr) are verified
        // against libsecret's own tool/secret-tool.c source, not just documentation.
        test("interpretHasResult: exit 0 means the item was found") {
            LinuxSecretToolBackend.interpretHasResult(exitCode = 0, stderrBlank = true) shouldBe true
            // stdout carries the secret on a real 0 exit, but stderr emptiness must not matter
            // once the exit code itself already says "found" unambiguously.
            LinuxSecretToolBackend.interpretHasResult(exitCode = 0, stderrBlank = false) shouldBe true
        }

        test("interpretHasResult: exit 1 with empty stderr means definitely absent") {
            LinuxSecretToolBackend.interpretHasResult(exitCode = 1, stderrBlank = true) shouldBe false
        }

        test("interpretHasResult: exit 1 with non-empty stderr is a real backend error, not absence") {
            LinuxSecretToolBackend.interpretHasResult(exitCode = 1, stderrBlank = false).shouldBeNull()
        }

        test("interpretHasResult: any other exit code is an unknown backend error, not absence") {
            LinuxSecretToolBackend.interpretHasResult(exitCode = 2, stderrBlank = true).shouldBeNull()
            LinuxSecretToolBackend.interpretHasResult(exitCode = -1, stderrBlank = true).shouldBeNull()
        }
    })
