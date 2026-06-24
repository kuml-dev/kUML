package dev.kuml.c4.dsl

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Unit tests for [C4Ids].
 *
 * Verifies deterministic ID generation after [C4Ids.resetForScript] and
 * correct behaviour of helper functions.
 *
 * V3.1.23
 */
class C4IdsTest :
    FunSpec({

        // ── generateId determinism ────────────────────────────────────────────

        test("generateId returns sequential IDs starting at c4-0 after reset") {
            C4Ids.resetForScript()
            C4Ids.generateId() shouldBe "c4-0"
            C4Ids.generateId() shouldBe "c4-1"
            C4Ids.generateId() shouldBe "c4-2"
        }

        test("resetForScript resets counter to c4-0") {
            C4Ids.resetForScript()
            C4Ids.generateId() // c4-0
            C4Ids.generateId() // c4-1
            C4Ids.resetForScript()
            C4Ids.generateId() shouldBe "c4-0"
        }

        test("withScriptContext resets counter inside the block") {
            C4Ids.resetForScript()
            C4Ids.generateId() // c4-0 — advances outer counter
            val innerFirst =
                C4Ids.withScriptContext {
                    C4Ids.generateId() // should be c4-0 in isolated context
                }
            innerFirst shouldBe "c4-0"
        }

        test("withScriptContext restores outer counter after block") {
            C4Ids.resetForScript()
            C4Ids.generateId() // c4-0
            C4Ids.withScriptContext {
                C4Ids.generateId() // c4-0 inside
                C4Ids.generateId() // c4-1 inside
            }
            // Outer counter was at 1 before withScriptContext; restored after
            val afterRestore = C4Ids.generateId()
            afterRestore shouldBe "c4-1"
        }

        test("two withScriptContext runs of same script produce identical first IDs") {
            val run1 = C4Ids.withScriptContext { listOf(C4Ids.generateId(), C4Ids.generateId()) }
            val run2 = C4Ids.withScriptContext { listOf(C4Ids.generateId(), C4Ids.generateId()) }
            run1 shouldBe run2
        }

        // ── child / relationship helpers ──────────────────────────────────────

        test("child with null parent returns name unchanged") {
            C4Ids.child(null, "Order") shouldBe "Order"
        }

        test("child with empty parent returns name unchanged") {
            C4Ids.child("", "Order") shouldBe "Order"
        }

        test("child with parent returns qualified id") {
            C4Ids.child("domain", "Order") shouldBe "domain::Order"
        }

        test("child with nested parent concatenates correctly") {
            C4Ids.child("domain::svc", "Order") shouldBe "domain::svc::Order"
        }

        test("relationship returns rel::<src>-><tgt>") {
            C4Ids.relationship("source-id", "target-id") shouldBe "rel::source-id->target-id"
        }

        // ── disambiguate ──────────────────────────────────────────────────────

        test("disambiguate returns candidate unchanged when not taken") {
            C4Ids.disambiguate("Customer", emptySet()) shouldBe "Customer"
        }

        test("disambiguate appends ~2 on first collision") {
            C4Ids.disambiguate("Customer", setOf("Customer")) shouldBe "Customer~2"
        }

        test("disambiguate increments suffix until unique") {
            C4Ids.disambiguate("Customer", setOf("Customer", "Customer~2", "Customer~3")) shouldBe "Customer~4"
        }

        test("generated IDs are non-empty strings") {
            C4Ids.resetForScript()
            repeat(10) {
                C4Ids.generateId() shouldNotBe ""
            }
        }
    })
