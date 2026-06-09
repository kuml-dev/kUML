package dev.kuml.expr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * V2.0.20b — tests for [OclLikeExpressionParser.parseEffects] and
 * [OclLikeExpressionParser.tryParseEffects].
 */
class KumlEffectParserTest :
    FunSpec({

        // ── 1. Simple dotted call ─────────────────────────────────────────────

        test("relay.heat(true) parses as single CallEffect") {
            val effects = OclLikeExpressionParser.parseEffects("relay.heat(true)")
            effects shouldHaveSize 1
            val effect = effects[0] as CallEffect
            effect.receiver shouldBe listOf("relay", "heat")
            effect.args shouldHaveSize 1
            effect.args[0] shouldBe LiteralBool(true)
        }

        // ── 2. Simple assignment ──────────────────────────────────────────────

        test("targetTemp = 21.0 parses as AssignEffect") {
            val effects = OclLikeExpressionParser.parseEffects("targetTemp = 21.0")
            effects shouldHaveSize 1
            val effect = effects[0] as AssignEffect
            effect.target shouldBe listOf("targetTemp")
            effect.value shouldBe LiteralReal(21.0)
        }

        // ── 3. Two calls separated by semicolon ───────────────────────────────

        test("relay.heat(true); log.info('done') parses as two CallEffects") {
            val effects = OclLikeExpressionParser.parseEffects("relay.heat(true); log.info('done')")
            effects shouldHaveSize 2
            val first = effects[0] as CallEffect
            first.receiver shouldBe listOf("relay", "heat")
            first.args[0] shouldBe LiteralBool(true)
            val second = effects[1] as CallEffect
            second.receiver shouldBe listOf("log", "info")
            second.args[0] shouldBe LiteralString("done")
        }

        // ── 4. Bare expression fallback ───────────────────────────────────────

        test("bare integer literal 42 wraps in ExpressionEffect") {
            val effects = OclLikeExpressionParser.parseEffects("42")
            effects shouldHaveSize 1
            val effect = effects[0] as ExpressionEffect
            effect.expr shouldBe LiteralInt(42)
        }

        // ── 5. Empty string returns empty list ────────────────────────────────

        test("empty string returns empty list") {
            val effects = OclLikeExpressionParser.parseEffects("")
            effects.shouldBeEmpty()
        }

        // ── 6. Unparseable input: tryParseEffects returns null ────────────────

        test("tryParseEffects returns null for unparseable input '@@@'") {
            val errors = mutableListOf<ParseError>()
            val result = OclLikeExpressionParser.tryParseEffects("@@@", errors)
            result.shouldBeNull()
        }

        // ── 7. Assignment with binary-op RHS ─────────────────────────────────

        test("a = b + c parses as AssignEffect with BinaryOp ADD") {
            val effects = OclLikeExpressionParser.parseEffects("a = b + c")
            effects shouldHaveSize 1
            val effect = effects[0] as AssignEffect
            effect.target shouldBe listOf("a")
            val rhs = effect.value as BinaryOp
            rhs.op shouldBe BinaryOperator.ADD
            rhs.left shouldBe AttributeRef(listOf("b"))
            rhs.right shouldBe AttributeRef(listOf("c"))
        }

        // ── 8. Single-segment call with string argument ───────────────────────

        test("display.show('idle') parses as CallEffect") {
            val effects = OclLikeExpressionParser.parseEffects("display.show('idle')")
            effects shouldHaveSize 1
            val effect = effects[0] as CallEffect
            effect.receiver shouldBe listOf("display", "show")
            effect.args shouldHaveSize 1
            effect.args[0] shouldBe LiteralString("idle")
        }
    })
