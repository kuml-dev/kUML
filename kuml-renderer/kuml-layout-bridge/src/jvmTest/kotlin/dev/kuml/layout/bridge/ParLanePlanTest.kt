package dev.kuml.layout.bridge

import dev.kuml.layout.PortSide
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection.In
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.pin
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The lane plan of nested parametric constraints: unique ranks, bounded counts, independent of binding order. */
class ParLanePlanTest :
    FunSpec({
        fun model(
            count: Int,
            reversed: Boolean,
        ): Sysml2Model =
            sysml2Model(name = "Lanes") {
                attributeDef(name = "Real")
                val outer =
                    constraintDef(
                        name = "Outer",
                        expression = "x",
                        parameters = (1..count).map { ConstraintParameter(name = "i$it", typeId = "Real", direction = In) },
                    )
                val inner =
                    constraintDef(
                        name = "Inner",
                        expression = "y",
                        parameters = (1..count).map { ConstraintParameter(name = "c$it", typeId = "Real", direction = In) },
                        parent = outer,
                    )
                val order = if (reversed) (1..count).reversed() else (1..count).toList()
                for (k in order) bind(name = "b$k", source = outer.pin("i$k"), target = inner.pin("c$k"))
                parDiagram(name = "D") {
                    include(definition = outer)
                    include(definition = inner)
                }
            }

        fun b(k: Int) = "binding:Outer::i$k::Inner::c$k"

        fun plan(m: Sysml2Model): ParLanePlan {
            val ids = m.definitions.map { it.id }.toSet()
            val nesting = ParNesting.of(model = m, visibleIds = ids)
            return ParLanePlan.of(model = m, nesting = nesting, visible = ids)
        }

        test("seven bindings in one owner get lanes 0..6 without repeats") {
            val p = plan(model(count = 7, reversed = false))
            (1..7).map { p.channelLane(b(it)) }.sorted() shouldBe (0..6).toList()
            p.channelCount("Outer") shouldBe 7
        }

        test("corridor and approach ranks are unique per side and the topmost pin is innermost / nearest") {
            val p = plan(model(count = 4, reversed = false))
            p.corridorCount(owner = "Outer", side = PortSide.West) shouldBe 4
            (0..3).map { p.corridorRank(owner = "Outer", side = PortSide.West, paramIndex = it) }.sortedBy { it } shouldBe
                listOf(0, 1, 2, 3)
            p.corridorRank(owner = "Outer", side = PortSide.West, paramIndex = 0) shouldBe 3
            (0..3).map { p.approachRank(child = "Inner", side = PortSide.West, paramIndex = it) } shouldBe listOf(0, 1, 2, 3)
            p.approachCount(child = "Inner", side = PortSide.West) shouldBe 4
        }

        test("the plan does not depend on the order of the bindings") {
            val a = plan(model(count = 6, reversed = false))
            val c = plan(model(count = 6, reversed = true))
            for (k in 1..6) {
                a.channelLane(b(k)) shouldBe c.channelLane(b(k))
                a.share(bindingId = b(k), end = 0) shouldBe c.share(bindingId = b(k), end = 0)
            }
        }

        test("no shifts when no pin is shared") {
            val p = plan(model(count = 3, reversed = false))
            (1..3).forEach { p.share(bindingId = b(it), end = 0) shouldBe 0f }
        }
    })
