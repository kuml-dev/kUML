@file:Suppress("RequireNamedArguments")

package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.io.latex.LatexSourceCheck
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.chain
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.end
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.model
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.start
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.task
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.toLatex
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain as collectionShouldContain

private val NODE_NAME = Regex("""\\node\[[^\]]*]\s*\(([A-Za-z_][A-Za-z0-9_]*)\)""")

/** Names of all `\node[...] (name)` definitions, in order. */
private fun definedNames(tex: String): List<String> = NODE_NAME.findAll(tex).map { it.groupValues[1] }.toList()

/** The TikZ y (in pt) of the named `\node ... (name) at (x,y)` definition. */
private fun yOf(
    tex: String,
    name: String,
): Float = Regex("""\(${Regex.escape(name)}\) at \(-?[0-9.]+pt,(-?[0-9.]+)pt\)""").find(tex)!!.groupValues[1].toFloat()

private fun xOf(
    tex: String,
    name: String,
): Float = Regex("""\(${Regex.escape(name)}\) at \((-?[0-9.]+)pt,-?[0-9.]+pt\)""").find(tex)!!.groupValues[1].toFloat()

class BpmnCollaborationLatexTest :
    StringSpec({
        "every flow node of a white-box pool is defined and every message flow endpoint resolves" {
            val tex = toLatex(BpmnCollaborationFixtures.orderFlow())
            LatexSourceCheck.undefinedNodeReferences(tex) shouldBe emptySet()
            definedNames(tex) shouldContainAll
                listOf("c_start", "c_wait", "c_end", "s_start", "s_pick", "s_ship", "s_end", "pool_c", "pool_s", "pool_x")
            tex shouldNotContain "inner process nodes omitted"
        }

        "sequence flows of the referenced processes are drawn inside the pools" {
            val tex = toLatex(BpmnCollaborationFixtures.orderFlow())
            tex shouldContain "\\draw[kuml-bpmn-flow] (c_start) -- (c_wait);"
            tex shouldContain "\\draw[kuml-bpmn-flow] (s_ship) -- (s_end);"
        }

        "message flows come after all node definitions" {
            val tex = toLatex(BpmnCollaborationFixtures.orderFlow())
            val lastNode = tex.lastIndexOf("\\node[")
            val firstMessage = tex.indexOf("\\draw[kuml-bpmn-msgflow]")
            (firstMessage > lastNode) shouldBe true
        }

        "flows into and out of a black-box pool end on its border, straight above or below the node" {
            val tex = toLatex(BpmnCollaborationFixtures.orderFlow())
            tex shouldContain "(s_ship.south) -- node[kuml-bpmn-label,midway,right=3pt] {Pickup Request} (s_ship.south |- pool_x.north)"
            tex shouldContain "(s_end.south |- pool_x.north) -- node[kuml-bpmn-label,midway,right=3pt] {Proof of Delivery} (s_end.south)"
        }

        "pools are stacked in distinct vertical bands and have the same width" {
            val tex = toLatex(BpmnCollaborationFixtures.orderFlow())
            val ys = listOf("pool_c", "pool_s", "pool_x").map { yOf(tex, it) }
            ys.map { -it }.shouldBeSorted()
            ys.toSet().size shouldBe 3
            val widths =
                Regex(
                    """kuml-bpmn-pool,anchor=north west,minimum width=([0-9.]+)pt""",
                ).findAll(tex).map { it.groupValues[1] }.toSet()
            widths.size shouldBe 1
        }

        "nested lanes get one band per leaf lane, increasing downwards, and nodes sit in the band of their lane" {
            val tex = toLatex(BpmnCollaborationFixtures.orderFlow())
            val laneYs = listOf("lane_office", "lane_sales", "lane_buy", "lane_logistics").map { yOf(tex, it) }
            // parent and first child start together; the second child, then the next lane, lie further down
            laneYs[0] shouldBe laneYs[1]
            (-laneYs[2] > -laneYs[1]) shouldBe true
            (-laneYs[3] > -laneYs[2]) shouldBe true
            // the first node column sits to the right of the two header columns
            (xOf(tex, "s_start") > 25f + 2 * 20f) shouldBe true
            // s_start: sales band, s_pick: purchasing band, s_ship/s_end: logistics band
            yOf(tex, "s_start") shouldBe yOf(tex, "lane_sales") - 40f
            yOf(tex, "s_pick") shouldBe yOf(tex, "lane_buy") - 40f
            yOf(tex, "s_ship") shouldBe yOf(tex, "lane_logistics") - 40f
            yOf(tex, "s_end") shouldBe yOf(tex, "s_ship")
        }

        "a node listed in no lane goes to the first band, and a lane reference to a missing node is ignored" {
            val process = chain(id = "p", name = "P", nodes = listOf(start("a"), task("b")))
            val tex =
                toLatex(
                    model(
                        processes = listOf(process),
                        participants =
                            listOf(
                                BpmnParticipant(
                                    id = "pool",
                                    processRef = "p",
                                    lanes =
                                        listOf(
                                            BpmnLane(id = "l1", flowNodeRefs = listOf("ghost")),
                                            BpmnLane(id = "l2", flowNodeRefs = listOf("b")),
                                        ),
                                ),
                            ),
                    ),
                )
            yOf(tex, "a") shouldBe yOf(tex, "l1") - 40f
            yOf(tex, "b") shouldBe yOf(tex, "l2") - 40f
            LatexSourceCheck.undefinedNodeReferences(tex) shouldBe emptySet()
        }

        "lanes without a process, and a processRef to a missing process, draw only the structure" {
            val tex =
                toLatex(
                    model(
                        processes = emptyList(),
                        participants =
                            listOf(
                                BpmnParticipant(id = "p1", name = "A", lanes = listOf(BpmnLane(id = "l1"), BpmnLane(id = "l2"))),
                                BpmnParticipant(id = "p2", name = "B", processRef = "nope"),
                            ),
                    ),
                )
            definedNames(tex).toSet() shouldBe setOf("p1", "ph_p1", "l1", "lh_l1", "l2", "lh_l2", "p2", "ph_p2")
            LatexSourceCheck.undefinedNodeReferences(tex) shouldBe emptySet()
        }

        "two message flows between the same two pools both resolve" {
            val a = chain(id = "pa", name = "A", nodes = listOf(start("a1"), end("a2")))
            val b = chain(id = "pb", name = "B", nodes = listOf(start("b1"), end("b2")))
            val tex =
                toLatex(
                    model(
                        processes = listOf(a, b),
                        participants =
                            listOf(
                                BpmnParticipant(id = "poolA", processRef = "pa"),
                                BpmnParticipant(id = "poolB", processRef = "pb"),
                            ),
                        messageFlows =
                            listOf(
                                MessageFlow(id = "m1", sourceRef = "a1", targetRef = "b1"),
                                MessageFlow(id = "m2", sourceRef = "b2", targetRef = "a2"),
                            ),
                    ),
                )
            Regex("""\\draw\[kuml-bpmn-msgflow]""").findAll(tex).count() shouldBe 2
            LatexSourceCheck.undefinedNodeReferences(tex) shouldBe emptySet()
        }

        "a typo in a message flow endpoint is still reported as an undefined node" {
            val a = chain(id = "pa", name = "A", nodes = listOf(start("a1")))
            val tex =
                toLatex(
                    model(
                        processes = listOf(a),
                        participants = listOf(BpmnParticipant(id = "poolA", processRef = "pa"), BpmnParticipant(id = "poolB")),
                        messageFlows = listOf(MessageFlow(id = "m", sourceRef = "a1", targetRef = "typo_id")),
                    ),
                )
            LatexSourceCheck.undefinedNodeReferences(tex) shouldBe setOf("typo_id")
        }

        "the same process used by two pools defines every node name once" {
            val p = chain(id = "p", name = "P", nodes = listOf(start("n1"), task("n2")))
            val tex =
                toLatex(
                    model(
                        processes = listOf(p),
                        participants =
                            listOf(
                                BpmnParticipant(id = "poolA", processRef = "p"),
                                BpmnParticipant(id = "poolB", processRef = "p"),
                            ),
                    ),
                )
            val names = definedNames(tex)
            names.size shouldBe names.toSet().size
            names.count { it == "n1" } shouldBe 1
            tex shouldContain "% some flow nodes omitted"
        }

        "name clashes between pool, lane and node ids never define a name twice" {
            val p = chain(id = "p", name = "P", nodes = listOf(start("pool"), task("lane"), task("---"), task("")))
            val tex =
                toLatex(
                    model(
                        processes = listOf(p),
                        participants =
                            listOf(
                                BpmnParticipant(
                                    id = "pool",
                                    processRef = "p",
                                    lanes = listOf(BpmnLane(id = "lane"), BpmnLane(id = "lane")),
                                ),
                                BpmnParticipant(id = "pool"),
                            ),
                    ),
                )
            val names = definedNames(tex)
            names.size shouldBe names.toSet().size
            // "---" sanitises to "___" (a valid name); the empty id has no usable name and is left out
            names collectionShouldContain "___"
            LatexSourceCheck.undefinedNodeReferences(tex) shouldBe emptySet()
        }

        "hostile pool, lane, process and node text is escaped and ids are sanitised" {
            val hostile = "\\input{/etc/passwd}\n% \\write18{x} } { # ^^5c"
            val p =
                chain(
                    id = "p-1",
                    name = hostile,
                    nodes = listOf(start("a b\n%\\input{x}", hostile), task("t\"1", hostile), end("e", hostile)),
                )
            val tex =
                toLatex(
                    model(
                        processes = listOf(p),
                        participants =
                            listOf(
                                BpmnParticipant(
                                    id = "pool\n%{",
                                    name = hostile,
                                    processRef = "p-1",
                                    lanes = listOf(BpmnLane(id = "l\\input", name = hostile, flowNodeRefs = listOf("t\"1"))),
                                ),
                            ),
                        messageFlows = listOf(MessageFlow(id = "m", name = hostile, sourceRef = "e", targetRef = "pool\n%{")),
                        name = hostile,
                    ),
                )
            tex shouldNotContain "\\input"
            tex shouldNotContain "\\write18"
            // every definition name is a plain identifier
            definedNames(tex).all { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) } shouldBe true
            // a comment line (the only place a newline could smuggle code) holds no stray TeX group
            tex.lines().filter { it.trimStart().startsWith("%") }.none { it.contains("\\node") } shouldBe true
        }

        "a very long process is cut below the TeX dimension limit" {
            val nodes = (1..200).map { task("n$it", "Task $it") }
            val tex =
                toLatex(
                    model(
                        processes = listOf(chain(id = "p", name = "P", nodes = nodes)),
                        participants = listOf(BpmnParticipant(id = "pool", processRef = "p")),
                    ),
                )
            val xs = Regex("""at \((-?[0-9.]+)pt,""").findAll(tex).map { it.groupValues[1].toFloat() }
            (xs.max() < 12000f) shouldBe true
            tex shouldContain "picture width limit reached"
            LatexSourceCheck.undefinedNodeReferences(tex) shouldBe emptySet()
            definedNames(tex).size shouldBeGreaterThan 20
        }

        "very many lanes are cut below the TeX dimension limit" {
            val lanes = (1..200).map { BpmnLane(id = "l$it") }
            val tex =
                toLatex(
                    model(
                        processes = emptyList(),
                        participants = listOf(BpmnParticipant(id = "pool", lanes = lanes), BpmnParticipant(id = "pool2")),
                    ),
                )
            val ys = Regex("""at \((-?[0-9.]+)pt,(-?[0-9.]+)pt\)""").findAll(tex).map { it.groupValues[2].toFloat() }
            (ys.min() > -12000f) shouldBe true
        }

        "annotation names never collide with flow node names" {
            // a flow node called like the note prefix: the note prefix must move away from it
            val p = chain(id = "p", name = "P", nodes = listOf(start("kumlnote0")))
            val tex = toLatex(model(processes = listOf(p), participants = listOf(BpmnParticipant(id = "pool", processRef = "p"))))
            definedNames(tex) collectionShouldContain "kumlnote0"
        }
    })
