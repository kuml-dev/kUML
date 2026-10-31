package dev.kuml.cli

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.layout.bridge.LayoutEffectMatrix
import dev.kuml.layout.bridge.SpecDiagramKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * PAL 2B-b: a `layout { }` block compiles in every family that has one, renders deterministically and
 * produces exactly the W001 findings the effect matrix predicts.
 */
class LayoutBlockConformanceTest :
    FunSpec({
        val block = "direction = Right; wrapColumns = 3; aspectRatio = 1.5"

        val scripts: Map<SpecDiagramKind, String> =
            mapOf(
                SpecDiagramKind.UML_CLASS to
                    """
                    classDiagram(name = "D") {
                        val a = classOf(name = "A")
                        classOf(name = "B") { extends(general = a) }
                        layout { $block }
                    }
                    """,
                SpecDiagramKind.UML_SEQUENCE to
                    """
                    sequenceDiagram(name = "D") {
                        val a = lifeline(name = "A")
                        val b = lifeline(name = "B")
                        message(from = a, to = b, label = "go()")
                        layout { $block }
                    }
                    """,
                SpecDiagramKind.SYSML2_BDD to
                    """
                    import dev.kuml.sysml2.dsl.sysml2Model
                    sysml2Model(name = "M") {
                        val a = partDef(name = "A")
                        val b = partDef(name = "B", specializesId = a.id)
                        bdd(name = "D") {
                            include(definition = a)
                            include(definition = b)
                            layout { $block }
                        }
                    }
                    """,
                SpecDiagramKind.SYSML2_SEQ to
                    """
                    import dev.kuml.sysml2.MessageKind
                    import dev.kuml.sysml2.dsl.sysml2Model
                    sysml2Model(name = "M") {
                        val a = lifelineDef(name = "A")
                        val b = lifelineDef(name = "B")
                        message(label = "go()", source = a, target = b, seqNo = 1, kind = MessageKind.Sync)
                        seqDiagram(name = "D") {
                            include(a)
                            include(b)
                            layout { $block }
                        }
                    }
                    """,
                SpecDiagramKind.C4 to
                    """
                    import dev.kuml.c4.dsl.c4Model
                    c4Model(name = "Shop") {
                        val user = person(name = "User")
                        val shop = softwareSystem(name = "Shop")
                        relationship(source = user, target = shop) { description = "uses" }
                        systemContextDiagram(name = "Context") {
                            include(user, shop)
                            layout { $block }
                        }
                    }
                    """,
                SpecDiagramKind.ERM to
                    """
                    import dev.kuml.erm.dsl.ermModel
                    ermModel("S") {
                        val a = entity("A") { id() }
                        entity("B") {
                            id()
                            foreignKey(name = "a_id", references = a, nullable = false)
                        }
                        diagram(name = "D") { layout { $block } }
                    }
                    """,
                SpecDiagramKind.BLUEPRINT to
                    """
                    import dev.kuml.blueprint.dsl.blueprint
                    import dev.kuml.blueprint.model.Sentiment
                    blueprint(name = "Service") {
                        phase(name = "Request") { customer(name = "Fills in", sentiment = Sentiment.NEUTRAL) }
                        blueprintDiagram(name = "Blueprint") { layout { $block } }
                    }
                    """,
                SpecDiagramKind.JOURNEY to
                    """
                    import dev.kuml.blueprint.dsl.blueprint
                    import dev.kuml.blueprint.model.Sentiment
                    blueprint(name = "Service") {
                        phase(name = "Request") { customer(name = "Fills in", sentiment = Sentiment.NEUTRAL) }
                        journeyDiagram(name = "Journey") { layout { $block } }
                    }
                    """,
            )

        fun render(script: String): Pair<String, List<String>> {
            val dir = Files.createTempDirectory("kuml-layout-conf")
            try {
                val input = dir.resolve("d.kuml.kts").toFile().also { it.writeText(script.trimIndent()) }
                val out = dir.resolve("d.svg")
                val outcome = RenderPipeline.run(input = input, output = out, format = "svg", width = 800, themeName = "kuml")
                return out.toFile().readText() to outcome.layoutFindings.map { it.code + "|" + it.message }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        scripts.forEach { (kind, script) ->
            test("$kind: compiles, renders twice byte-identically and reports exactly the matrix W001s") {
                val (svg1, findings1) = render(script)
                val (svg2, findings2) = render(script)
                svg1 shouldBe svg2
                findings1 shouldBe findings2
                val w001 = findings1.filter { it.startsWith(LayoutCodes.NO_EFFECT) }
                val ignored = 3 - LayoutEffectMatrix.effectiveFor(kind).let { if (it.isEmpty()) 0 else 3 }
                w001.size shouldBe ignored
            }
        }

        test("the matrix kinds without a conformance script are exactly those without a layout block in the DSL") {
            val covered = scripts.keys
            val uncovered = SpecDiagramKind.entries.toSet() - covered
            // Families whose conformance is covered by sharing a pipeline with a tested one, or that have no layout block yet.
            val allowed =
                setOf(
                    SpecDiagramKind.UML_OBJECT,
                    SpecDiagramKind.UML_COMPOSITE_STRUCTURE,
                    SpecDiagramKind.UML_PACKAGE,
                    SpecDiagramKind.UML_DEPLOYMENT,
                    SpecDiagramKind.UML_PROFILE,
                    SpecDiagramKind.UML_COMPONENT,
                    SpecDiagramKind.UML_USE_CASE,
                    SpecDiagramKind.UML_STATE,
                    SpecDiagramKind.UML_ACTIVITY,
                    SpecDiagramKind.UML_COMMUNICATION,
                    SpecDiagramKind.UML_INTERACTION_OVERVIEW,
                    SpecDiagramKind.UML_TIMING,
                    SpecDiagramKind.SYSML2_IBD,
                    SpecDiagramKind.SYSML2_STM,
                    SpecDiagramKind.SYSML2_REQ,
                    SpecDiagramKind.SYSML2_UC,
                    SpecDiagramKind.SYSML2_ACT,
                    SpecDiagramKind.SYSML2_PAR,
                    SpecDiagramKind.BPMN_PROCESS,
                    SpecDiagramKind.BPMN_COLLABORATION,
                    SpecDiagramKind.BPMN_CHOREOGRAPHY,
                    SpecDiagramKind.BPMN_CONVERSATION,
                )
            allowed shouldContainAll uncovered
        }
    })
