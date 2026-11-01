package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.StmSeverity
import dev.kuml.sysml2.constraint.Sysml2StmChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/** PAL wave 2D-b: the optional `invariant` of a state (DSL, JSON, printer, checker rules 131 and 129). */
class Sysml2StateInvariantTest :
    FunSpec({
        fun model(
            invariant: String?,
            entry: String? = null,
        ) = sysml2Model(name = "M") {
            val a = stateDef(name = "A", invariant = invariant, entryAction = entry)
            val b = stateDef(name = "B")
            transition(name = "t", source = a, target = b)
            stmDiagram(name = "d") {
                include(a)
                include(b)
            }
        }

        fun stm(m: Sysml2Model) = m.diagrams.filterIsInstance<StmDiagram>().single()

        test("the DSL stores the invariant on the state definition") {
            val a = model(invariant = "x > 0").definitions.filterIsInstance<StateDefinition>().first { it.id == "A" }
            a.invariant shouldBe "x > 0"
        }

        test("JSON round trip keeps the invariant and null stays absent") {
            val withInv = model(invariant = "x > 0")
            Json.decodeFromString(Sysml2Model.serializer(), Json.encodeToString(Sysml2Model.serializer(), withInv)) shouldBe withInv
            val without = model(invariant = null)
            val json = Json { encodeDefaults = false }
            json.encodeToString(Sysml2Model.serializer(), without) shouldNotContain "invariant"
            // old documents (no field) still load
            Json.decodeFromString(Sysml2Model.serializer(), json.encodeToString(Sysml2Model.serializer(), without)) shouldBe without
        }

        test("the DSL printer writes the invariant argument") {
            val text = Sysml2DslPrinter.print(model(invariant = "x > 0"))
            text shouldContain "invariant = \"x > 0\""
        }

        test("rule 131: a blank invariant is an error, a normal one is clean") {
            val bad = Sysml2StmChecker.check(model = model(invariant = "   "), diagram = stm(model(invariant = "   ")))
            bad.single { it.code == 131 }.severity shouldBe StmSeverity.Error
            val m = model(invariant = "x > 0")
            Sysml2StmChecker.check(model = m, diagram = stm(m)).filter { it.code == 131 }.shouldBeEmpty()
        }

        test("rule 129: an over-long invariant or action text is an error") {
            val long = "x".repeat(STM_MAX_TEXT_LEN + 1)
            val m = model(invariant = long)
            Sysml2StmChecker.check(model = m, diagram = stm(m)).any { it.code == 129 } shouldBe true
            val m2 = model(invariant = null, entry = long)
            Sysml2StmChecker.check(model = m2, diagram = stm(m2)).any { it.code == 129 } shouldBe true
        }
    })
