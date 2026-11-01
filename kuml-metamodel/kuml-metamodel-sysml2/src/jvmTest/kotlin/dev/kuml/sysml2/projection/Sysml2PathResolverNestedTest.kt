package dev.kuml.sysml2.projection

import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** PAL wave 2C-b2: nested part paths (`a.b.c`) resolve to the deepest part; depth and node count are bounded. */
class Sysml2PathResolverNestedTest :
    FunSpec(body = {
        val model =
            sysml2Model(name = "M") {
                portDef(name = "Sig")
                partDef(name = "Leaf") { port(name = "p", typeId = "Sig", direction = PortDirection.In) }
                partDef(name = "Mid") { part(name = "leaf", typeId = "Leaf") }
                partDef(name = "Top") { part(name = "mid", typeId = "Mid") }
                partDef(name = "Owner") {
                    part(name = "top", typeId = "Top")
                    connect(name = "c", typeId = "Sig", sourceEndId = "top.mid.leaf.p", targetEndId = "top.mid.leaf.p")
                }
            }
        val visible = setOf("Owner::top")
        val nested = setOf("Owner::top::mid", "Owner::top::mid::leaf")

        test("a three level path resolves to the deepest nested part and its port") {
            val r =
                Sysml2PathResolver.resolve(
                    model = model,
                    ownerId = "Owner",
                    endpointId = "Owner::top::mid::leaf::p",
                    visibleNodeIds = visible,
                    nestedNodeIds = nested,
                )!!
            r.nodeId shouldBe "Owner::top::mid::leaf"
            r.steps shouldContainExactly listOf("p")
            r.terminalPort?.name shouldBe "p"
        }

        test("without nested ids the same path stays on the visible top part (old behaviour)") {
            val r =
                Sysml2PathResolver.resolve(
                    model = model,
                    ownerId = "Owner",
                    endpointId = "Owner::top::mid::leaf::p",
                    visibleNodeIds = visible,
                )!!
            r.nodeId shouldBe "Owner::top"
            r.steps shouldContainExactly listOf("mid", "leaf", "p")
            r.terminalPort?.name shouldBe "p"
        }

        test("the projection lists nested paths parents first and knows each parent") {
            val p = sysml2IbdProjection(model = model, diagram = IbdDiagram(name = "d", ownerId = "Owner"))
            p.nestedPartPaths shouldContainExactly listOf("Owner::top::mid", "Owner::top::mid::leaf")
            p.parentOf["Owner::top::mid::leaf"] shouldBe "Owner::top::mid"
            p.parentOf["Owner::top::mid"] shouldBe "Owner::top"
        }

        test("a recursive type ends at the depth limit instead of looping") {
            val m =
                sysml2Model(name = "R") {
                    partDef(name = "Node") { part(name = "next", typeId = "Node") }
                    partDef(name = "Owner") { part(name = "n", typeId = "Node") }
                }
            val p = sysml2IbdProjection(model = m, diagram = IbdDiagram(name = "d", ownerId = "Owner"))
            p.nestedPartPaths shouldHaveSize (Sysml2PathResolver.MAX_SEGMENTS - 1)
        }

        test("a very wide type tree is capped at the node limit") {
            val m =
                sysml2Model(name = "W") {
                    partDef(name = "Cell")
                    partDef(name = "Wide") { for (i in 0 until 40) part(name = "c$i", typeId = "Cell") }
                    partDef(name = "Owner") { for (i in 0 until 12) part(name = "w$i", typeId = "Wide") }
                }
            val p = sysml2IbdProjection(model = m, diagram = IbdDiagram(name = "d", ownerId = "Owner"))
            (p.nestedPartPaths.size <= 256) shouldBe true
        }
    })
