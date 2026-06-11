package dev.kuml.runtime.sandbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SandboxPolicyTest :
    FunSpec({
        test("default policy has expected values") {
            val p = SandboxPolicy()
            p.guardTimeoutMs shouldBe SandboxPolicy.DEFAULT_GUARD_TIMEOUT_MS
            p.maxVariableCount shouldBe SandboxPolicy.DEFAULT_MAX_VARIABLE_COUNT
            p.maxStringLength shouldBe SandboxPolicy.DEFAULT_MAX_STRING_LENGTH
            p.maxEffectsPerAction shouldBe SandboxPolicy.DEFAULT_MAX_EFFECTS_PER_ACTION
            p.maxExpressionDepth shouldBe SandboxPolicy.DEFAULT_MAX_EXPRESSION_DEPTH
            p.allowedFunctions shouldBe SandboxPolicy.DEFAULT_ALLOWED_FUNCTIONS
        }

        test("Strict preset has tighter limits") {
            val strict = SandboxPolicy.Strict
            strict.guardTimeoutMs shouldBe 50L
            strict.maxVariableCount shouldBe 64
            strict.maxStringLength shouldBe 1_024
            strict.allowedFunctions shouldBe emptySet()
            strict.maxEffectsPerAction shouldBe 8
            strict.maxExpressionDepth shouldBe 16
        }

        test("Permissive preset has generous limits") {
            val p = SandboxPolicy.Permissive
            p.guardTimeoutMs shouldBe 1_000L
            p.maxVariableCount shouldBe 65_536
            p.maxStringLength shouldBe 1_048_576
            p.allowedFunctions shouldBe SandboxPolicy.DEFAULT_ALLOWED_FUNCTIONS
            p.maxEffectsPerAction shouldBe 256
            p.maxExpressionDepth shouldBe 64
        }
    })
