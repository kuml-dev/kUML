package dev.kuml.layout.bridge

import dev.kuml.layout.GroupId
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.RequirementPriority
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/** Wave C1 — geometry assertions only (no pixels, no glyph widths). */
class Sysml2C1BridgeTest :
    FunSpec({
        test("wrap: never loses characters, hard-splits long words, respects max chars") {
            val w = "A".repeat(75)
            val lines = Sysml2ReqCard.wrap(text = "$w short words here", maxChars = 30)
            lines.all { it.length <= 30 } shouldBe true
            lines.joinToString("").replace(" ", "") shouldBe "${w}shortwordshere"
            Sysml2ReqCard.wrap(text = "", maxChars = 10) shouldBe emptyList()
        }

        test("requirement card grows in height with title and text, width within 160..240") {
            val short = Sysml2ReqCard.forRequirement(RequirementDefinition(id = "a", name = "A"))
            val long =
                Sysml2ReqCard.forRequirement(
                    RequirementDefinition(
                        id = "b",
                        name = "Very long requirement title ".repeat(4),
                        text = "word ".repeat(80),
                        priority = RequirementPriority.HIGH,
                    ),
                )
            (long.height > short.height) shouldBe true
            (long.titleLines.size > 1) shouldBe true
            (long.width in 160f..240f) shouldBe true
            (short.width in 160f..240f) shouldBe true
            long.bodyLines.last() shouldBe "priority: High"
        }

        test("use case description: max 4 lines, truncated flag") {
            val model = sysml2Model(name = "M") { useCaseDef(name = "U", description = "long ".repeat(200)) }
            val uc = model.definitions.filterIsInstance<dev.kuml.sysml2.UseCaseDefinition>().single()
            val d = Sysml2ReqCard.ucDescription(uc = uc, ellipseWidth = 200f)
            d.lines.size shouldBe 4
            d.truncated shouldBe true
            d.lines.last().endsWith("…") shouldBe true
        }

        test("REQ graph: groups nest, depth is flattened to 3, empty groups dropped, subject/refine/trace/copy edges") {
            val model =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A", subjectRef = "P")
                    val b = requirementDef(name = "B")
                    val p = partDef(name = "P")
                    reqDiagram(name = "R") {
                        include(p)
                        include(a)
                        include(b)
                        refine(source = p, requirement = a)
                        trace(source = a, target = b)
                        copy(source = a, target = b)
                        group(name = "L1") { group(name = "L2") { group(name = "L3") { group(name = "L4") { include(b) } } } }
                        group(name = "Empty") {}
                    }
                }
            val diagram = model.diagrams.filterIsInstance<ReqDiagram>().single()
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
            g.groups.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("group:L1", "group:L1/L2", "group:L1/L2/L3", "group:L1/L2/L3/L4")
            val byId = g.groups.associateBy { it.id.value }
            byId.getValue("group:L1").parent shouldBe null
            byId.getValue("group:L1/L2").parent shouldBe GroupId("group:L1")
            byId.getValue("group:L1/L2/L3").parent shouldBe GroupId("group:L1/L2")
            // depth 4 is flattened to depth 3: sibling of L3
            byId.getValue("group:L1/L2/L3/L4").parent shouldBe GroupId("group:L1/L2")
            g.nodes.single { it.id.value == "B" }.groupId shouldBe GroupId("group:L1/L2/L3/L4")
            g.edges.map { it.id.value } shouldContainExactlyInAnyOrder
                listOf("refine:P::A", "trace:A::B", "copy:A::B", "subject:A::P")
        }

        test("UC graph: use cases are children of the subject frame, actors are not") {
            val model =
                sysml2Model(name = "M") {
                    val actor = actorDef(name = "Act")
                    val admin = actorDef(name = "Adm")
                    val uc = useCaseDef(name = "UC")
                    val other = useCaseDef(name = "Other")
                    ucDiagram(name = "U") {
                        include(actor)
                        include(admin)
                        include(uc)
                        include(other)
                        association(actor = actor, useCase = uc)
                        generalize(specific = admin, general = actor)
                        subject(name = "Sys") { include(uc) }
                    }
                }
            val diagram = model.diagrams.filterIsInstance<UcDiagram>().single()
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
            g.groups
                .single()
                .id.value shouldBe "ucsubject:Sys"
            g.nodes.single { it.id.value == "UC" }.groupId shouldBe GroupId("ucsubject:Sys")
            g.nodes.filter { it.id.value != "UC" }.all { it.groupId == null } shouldBe true
            g.edges.map { it.id.value } shouldContainExactlyInAnyOrder listOf("assoc:Act::UC", "generalization:Adm::Act")
        }

        test("DoS guard: at most 64 groups are laid out") {
            val model =
                sysml2Model(name = "M") {
                    val a = requirementDef(name = "A")
                    reqDiagram(name = "R") {
                        include(a)
                        repeat(100) { i -> group(name = "g$i") { include(a) } }
                    }
                }
            val g = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = model.diagrams.filterIsInstance<ReqDiagram>().single())
            (g.groups.size <= 64) shouldBe true
        }
    })
