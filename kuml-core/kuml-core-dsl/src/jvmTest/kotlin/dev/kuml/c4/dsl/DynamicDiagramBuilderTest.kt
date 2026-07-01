package dev.kuml.c4.dsl

import dev.kuml.c4.model.DynamicDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DynamicDiagramBuilderTest :
    FunSpec(body = {

        test("dynamic diagram captures interactions in declaration order") {
            val model =
                c4Model(name = "Test") {
                    val user = person(name = "User")
                    val web = softwareSystem(name = "WebApp")
                    val db = softwareSystem(name = "Database")
                    dynamicDiagram(name = "Checkout") {
                        interaction(description = "Open page", from = user, to = web)
                        interaction(description = "Query items", from = web, to = db)
                        response(description = "Item rows", from = db, to = web)
                        response(description = "HTML", from = web, to = user)
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<DynamicDiagram>()
            diag.interactions shouldHaveSize 4
            diag.interactions[0].description shouldBe "Open page"
            diag.interactions[0].sequence shouldBe 1
            diag.interactions[3].sequence shouldBe 4
            diag.interactions[0].response shouldBe false
            diag.interactions[2].response shouldBe true
        }

        test("participating elements are collected uniquely") {
            val model =
                c4Model(name = "Test") {
                    val a = person(name = "A")
                    val b = softwareSystem(name = "B")
                    dynamicDiagram(name = "Flow") {
                        interaction(description = "1", from = a, to = b)
                        interaction(description = "2", from = b, to = a)
                        interaction(description = "3", from = a, to = b)
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<DynamicDiagram>()
            diag.elements shouldHaveSize 2
        }

        test("technology is preserved on interactions") {
            val model =
                c4Model(name = "Test") {
                    val a = person(name = "A")
                    val b = softwareSystem(name = "B")
                    dynamicDiagram(name = "Tech") {
                        interaction(
                            description = "REST call",
                            from = a,
                            to = b,
                            technology = "HTTPS/JSON",
                        )
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<DynamicDiagram>()
            diag.interactions[0].technology shouldBe "HTTPS/JSON"
        }

        test("name and description are set on the resulting diagram") {
            val model =
                c4Model(name = "Test") {
                    val a = person(name = "A")
                    val b = softwareSystem(name = "B")
                    dynamicDiagram(name = "Login", description = "User login flow") {
                        interaction(description = "Submit", from = a, to = b)
                    }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<DynamicDiagram>()
            diag.name shouldBe "Login"
            diag.description shouldBe "User login flow"
        }

        test("empty dynamic diagram has no interactions or elements") {
            val model =
                c4Model(name = "Test") {
                    dynamicDiagram(name = "Empty") { }
                }
            val diag = model.diagrams[0].shouldBeInstanceOf<DynamicDiagram>()
            diag.interactions shouldHaveSize 0
            diag.elements shouldHaveSize 0
        }

        test("title() and note() are accepted without throwing") {
            val model =
                c4Model(name = "Test") {
                    val a = person(name = "A")
                    val b = softwareSystem(name = "B")
                    dynamicDiagram(name = "WithNotes") {
                        title(text = "Title")
                        note(text = "A note")
                        interaction(description = "msg", from = a, to = b)
                    }
                }
            model.diagrams shouldHaveSize 1
        }
    })
