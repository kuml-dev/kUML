package dev.kuml.web.layout

import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.layout.bridge.LayoutHintWriter
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.dsl.print.UmlModelDslPrinter
import dev.kuml.web.api.parseUmlDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class LayoutHintServiceTest :
    FunSpec({

        val service =
            LayoutHintService(
                parse = ::parseUmlDiagram,
                print = UmlModelDslPrinter::print,
            )

        val umlScript =
            """
            classDiagram(name = "Test") {
                classOf(name = "Alpha")
                classOf(name = "Beta")
            }
            """.trimIndent()

        test("happy path drops Alpha onto (1, 0) and round-trips through the parser") {
            val result = service.applyDrop(umlScript, "Alpha", LayoutHintWriter.GridCell(1, 0))
            result.isSuccess.shouldBeTrue()
            val script = result.getOrThrow()
            script shouldContain "classOf(name = \"Alpha\""
            script shouldContain "layout {"
            script shouldContain "col = 1"
            script shouldContain "row = 0"

            // True round-trip: re-feed the printed script through the parser and
            // assert the grid hint survived on the same element id.
            val reparsed = parseUmlDiagram(script)
            val alpha = reparsed.elements.first { it.id == "Alpha" } as UmlNamedElement
            (alpha.metadata[LayoutMetadataKeys.GRID_COL] as KumlMetaValue.Integer).value shouldBe 1L
            (alpha.metadata[LayoutMetadataKeys.GRID_ROW] as KumlMetaValue.Integer).value shouldBe 0L
        }

        test("applying the same drop twice is idempotent") {
            val once = service.applyDrop(umlScript, "Alpha", LayoutHintWriter.GridCell(2, 3)).getOrThrow()
            val twice = service.applyDrop(once, "Alpha", LayoutHintWriter.GridCell(2, 3)).getOrThrow()
            once shouldBe twice
        }

        test("non-UML script is rejected") {
            val c4Script =
                """
                c4Model(name = "TestSystem") {
                    val user = person(name = "User")
                    val sys = softwareSystem(name = "System") {
                        container(name = "Web") {
                            technology = "Ktor"
                        }
                    }
                    relationship(source = user, target = sys) { technology = "HTTPS" }
                    containerDiagram(name = "Test Container View") {
                        system = sys
                        showExternalSystems = false
                    }
                }
                """.trimIndent()
            val result = service.applyDrop(c4Script, "User", LayoutHintWriter.GridCell(0, 0))
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull()?.message shouldContain "UML"
        }

        test("non-class UML diagram is rejected") {
            val stateScript =
                """
                stateDiagram("OrderSM") {
                    initialState("Init")
                    state("Draft")
                }
                """.trimIndent()
            val result = service.applyDrop(stateScript, "Draft", LayoutHintWriter.GridCell(0, 0))
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull()?.message shouldContain "class diagram"
        }

        test("unknown element id is rejected") {
            val result = service.applyDrop(umlScript, "DoesNotExist", LayoutHintWriter.GridCell(0, 0))
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull()?.message shouldContain "DoesNotExist"
        }

        test("relationship/edge target is rejected") {
            val scriptWithAssociation =
                """
                classDiagram(name = "Test") {
                    val a = classOf(name = "Alpha")
                    val b = classOf(name = "Beta")
                    association(source = a, target = b)
                }
                """.trimIndent()
            val diagram = parseUmlDiagram(scriptWithAssociation)
            val assocId =
                diagram.elements
                    .filterIsInstance<UmlAssociation>()
                    .single()
                    .id

            val result = service.applyDrop(scriptWithAssociation, assocId, LayoutHintWriter.GridCell(0, 0))
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull()?.message shouldContain "relationship/edge"
        }

        test("dropping onto an already-occupied cell is rejected") {
            val occupied = service.applyDrop(umlScript, "Beta", LayoutHintWriter.GridCell(1, 0)).getOrThrow()
            val result = service.applyDrop(occupied, "Alpha", LayoutHintWriter.GridCell(1, 0))
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull()?.message shouldContain "Beta"
        }
    })
