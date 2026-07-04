package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Round-trip tests for [ExtractedDiagramCodec] — the wire format that carries an
 * [ExtractedDiagram] across the child-process IPC boundary. If a metamodel does
 * not survive encode→decode, the child-process evaluator would silently return
 * a degraded diagram; these tests guard that.
 *
 * V0.23.3.
 */
class ExtractedDiagramCodecTest :
    FunSpec({

        fun roundTrip(source: String): ExtractedDiagram {
            val evaluated = InProcessScriptEvaluator.evaluate(source)
            val success = evaluated.shouldBeInstanceOf<EvaluatedScript.Success>()
            return ExtractedDiagramCodec.decode(ExtractedDiagramCodec.encode(success.diagram))
        }

        test("UML diagram survives round-trip") {
            val decoded =
                roundTrip(
                    """
                    diagram(name = "Shop", type = DiagramType.CLASS) {
                        classOf("Order")
                    }
                    """.trimIndent(),
                )
            val uml = decoded.shouldBeInstanceOf<ExtractedDiagram.Uml>()
            uml.diagram.name shouldBe "Shop"
            uml.diagram.elements.any { (it as? dev.kuml.uml.UmlClass)?.name == "Order" } shouldBe true
        }

        test("C4 model survives round-trip and keeps the selected diagram") {
            val decoded =
                roundTrip(
                    """
                    c4Model(name = "Bank") {
                        val sys = softwareSystem("Internet Banking")
                        systemContextDiagram(name = "Context") {
                            include(sys)
                        }
                    }
                    """.trimIndent(),
                )
            val c4 = decoded.shouldBeInstanceOf<ExtractedDiagram.C4>()
            // The decoded diagram must be one of the model's own diagrams (index-selected).
            c4.model.diagrams.contains(c4.diagram) shouldBe true
        }

        test("SysML 2 model survives round-trip") {
            val decoded =
                roundTrip(
                    """
                    import dev.kuml.sysml2.dsl.sysml2Model

                    sysml2Model("Light") {
                        val initial = stateDef("Initial", isInitial = true)
                        val red = stateDef("Red")
                        transition("init", initial, red)
                        stmDiagram("Light STM") {
                            include(initial)
                            include(red)
                        }
                    }
                    """.trimIndent(),
                )
            val s2 = decoded.shouldBeInstanceOf<ExtractedDiagram.Sysml2>()
            s2.model.diagrams.contains(s2.diagram) shouldBe true
        }
    })
