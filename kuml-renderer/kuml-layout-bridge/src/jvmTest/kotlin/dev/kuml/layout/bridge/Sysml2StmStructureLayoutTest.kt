package dev.kuml.layout.bridge

import dev.kuml.layout.GroupId
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class Sysml2StmStructureLayoutTest :
    FunSpec({
        fun diagram(m: dev.kuml.sysml2.Sysml2Model) = m.diagrams.filterIsInstance<StmDiagram>().single()

        test("flat machine keeps the legacy graph: no groups") {
            val m =
                sysml2Model(name = "F") {
                    val a = stateDef(name = "A", isInitial = true)
                    val b = stateDef(name = "B")
                    transition(name = "t", source = a, target = b)
                    stmDiagram(name = "d") {
                        include(a)
                        include(b)
                    }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram(m))
            g.groups.size shouldBe 0
            g.nodes.map { it.id.value } shouldBe listOf("A", "B")
            g.edges.size shouldBe 1
        }

        test("composite and regions become nested compound groups; pseudo sizes") {
            val m =
                sysml2Model(name = "S") {
                    val p = stateDef(name = "P")
                    val r1 = region(owner = p, name = "L")
                    val r2 = region(owner = p, name = "R")
                    stateDef(name = "a", parent = p, region = r1)
                    stateDef(name = "b", parent = p, region = r2)
                    val f = fork(name = "F")
                    val c = choice(name = "C")
                    val j = junction(name = "J")
                    val h = history(name = "H", parent = p, region = r1)
                    transition(name = "t", source = f, target = c)
                    transition(name = "u", source = c, target = j)
                    transition(name = "v", source = h, target = j)
                    stmDiagram(name = "d") {
                        include(p)
                        include(f)
                        include(c)
                        include(j)
                    }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram(m))
            g.groups.map { it.id.value } shouldContain "P"
            g.groups.first { it.id.value == "P.L" }.parent shouldBe GroupId("P")
            g.nodes.first { it.id.value == "a" }.groupId shouldBe GroupId("P.L")
            g.nodes
                .first { it.id.value == "C" }
                .intrinsicSize.width shouldBe Sysml2LayoutBridge.STM_CHOICE_SIZE
            g.nodes
                .first { it.id.value == "J" }
                .intrinsicSize.width shouldBe Sysml2LayoutBridge.STM_JUNCTION_SIZE
            g.nodes
                .first { it.id.value == "H" }
                .intrinsicSize.width shouldBe Sysml2LayoutBridge.STM_HISTORY_SIZE
            val bar = g.nodes.first { it.id.value == "F" }.intrinsicSize
            bar.height shouldBe Sysml2LayoutBridge.STM_BAR_THICKNESS
            // layout-only ordering edge between the two regions
            g.edges.any { it.id.value.startsWith(Sysml2LayoutBridge.STM_ORDER_EDGE_PREFIX) } shouldBe true
        }

        test("fork/join bar grows with edges, capped, and rotates with the layout direction") {
            Sysml2LayoutBridge.stmForkJoinBarSize(transitionCount = 2, layoutDirection = LayoutDirection.TopToBottom).width shouldBe 64f
            Sysml2LayoutBridge.stmForkJoinBarSize(transitionCount = 4, layoutDirection = LayoutDirection.TopToBottom).width shouldBe 92f
            Sysml2LayoutBridge.stmForkJoinBarSize(transitionCount = 99, layoutDirection = LayoutDirection.TopToBottom).width shouldBe 160f
            val lr = Sysml2LayoutBridge.stmForkJoinBarSize(transitionCount = 3, layoutDirection = LayoutDirection.LeftToRight)
            lr.width shouldBe 6f
            lr.height shouldBe 78f
        }

        test("after a real layout the children lie inside their composite and regions do not overlap") {
            val m =
                sysml2Model(name = "S") {
                    val p = stateDef(name = "P")
                    val r1 = region(owner = p, name = "L")
                    val r2 = region(owner = p, name = "R")
                    val a = stateDef(name = "a", parent = p, region = r1)
                    val b = stateDef(name = "b", parent = p, region = r2)
                    val s = stateDef(name = "S", isInitial = true)
                    transition(name = "t", source = s, target = a)
                    transition(name = "u", source = s, target = b)
                    stmDiagram(name = "d") {
                        include(s)
                        include(p)
                    }
                }
            val lr =
                ElkLayoutEngine().layout(
                    graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram(m)),
                    hints = LayoutHints.DEFAULT,
                )
            val pb = lr.groups[GroupId("P")]!!.bounds
            for (id in listOf("a", "b")) {
                val n = lr.nodes[NodeId(id)]!!.bounds
                (n.origin.x >= pb.origin.x && n.origin.y >= pb.origin.y) shouldBe true
                (n.origin.x + n.size.width <= pb.origin.x + pb.size.width + 0.5f) shouldBe true
                (n.origin.y + n.size.height <= pb.origin.y + pb.size.height + 0.5f) shouldBe true
            }
            val l = lr.groups[GroupId("P.L")]!!.bounds
            val r = lr.groups[GroupId("P.R")]!!.bounds
            val overlap =
                l.origin.x < r.origin.x + r.size.width &&
                    r.origin.x < l.origin.x + l.size.width &&
                    l.origin.y < r.origin.y + r.size.height &&
                    r.origin.y < l.origin.y + l.size.height
            overlap shouldBe false
        }

        test("limits are enforced before layout") {
            val m =
                sysml2Model(name = "D") {
                    var prev = stateDef(name = "s0")
                    for (i in 1..17) prev = stateDef(name = "s$i", parent = prev)
                    stmDiagram(name = "d") { includeById("s0") }
                }
            shouldThrow<IllegalArgumentException> { Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram(m)) }
        }
    })
