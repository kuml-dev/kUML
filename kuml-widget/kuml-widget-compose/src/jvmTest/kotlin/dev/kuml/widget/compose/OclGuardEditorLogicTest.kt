package dev.kuml.widget.compose

import dev.kuml.core.ocl.OclCheckResult
import dev.kuml.core.ocl.OclScope
import dev.kuml.core.ocl.OclSyntax
import dev.kuml.core.ocl.OclType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure logic tests for [guardSaveEnabled] — the non-Composable decision logic
 * extracted from [OclGuardEditor]. The `@Composable` body itself, debounce
 * timing, and rendered button behavior are deferred to Wave 6's Compose
 * UI/robot tests (explicitly deferred, not silently skipped).
 */
class OclGuardEditorLogicTest :
    FunSpec(body = {

        test(name = "Ok => save enabled") {
            guardSaveEnabled(OclCheckResult.Ok) shouldBe true
        }

        test(name = "Error => save disabled") {
            guardSaveEnabled(OclCheckResult.Error("x", null)) shouldBe false
        }

        test(name = "save predicate tracks typeCheck on a realistic scope") {
            val scope = OclScope(mapOf("count" to OclType.INTEGER))

            guardSaveEnabled(OclSyntax.typeCheck("count > 0", scope)) shouldBe true
            guardSaveEnabled(OclSyntax.typeCheck("nope > 0", scope)) shouldBe false
        }

        test(name = "sandbox size guard is reachable through the editor's check path") {
            val emptyScope = OclScope(emptyMap())
            guardSaveEnabled(OclSyntax.typeCheck("a".repeat(5000), emptyScope)) shouldBe false
        }
    })
