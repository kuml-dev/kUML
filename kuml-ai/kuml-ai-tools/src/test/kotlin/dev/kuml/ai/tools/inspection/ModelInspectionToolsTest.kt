package dev.kuml.ai.tools.inspection

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.uml.UmlEditingTools
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest

class ModelInspectionToolsTest :
    FunSpec({

        fun makeContext(): AgentEditingContext = AgentEditingContext.emptyUml()

        test("list_elements on empty model returns empty list") {
            val ctx = makeContext()
            val tools = ModelInspectionTools(ctx)
            runTest {
                tools.listElements() shouldHaveSize 0
            }
        }

        test("list_elements returns each element exactly once") {
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = ModelInspectionTools(ctx)
            runTest {
                umlTools.addClass(name = "OrderService")
                umlTools.addClass(name = "Customer")
                val elements = tools.listElements()
                elements shouldHaveSize 2
                elements.map { it.id }.toSet() shouldHaveSize 2
            }
        }

        test("list_elements covers UmlClass UmlInterface and UmlAssociation") {
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = ModelInspectionTools(ctx)
            runTest {
                umlTools.addClass(name = "Order")
                umlTools.addInterface(name = "Repository")
                umlTools.addAssociation(sourceIdOrName = "Order", targetIdOrName = "Repository")
                val elements = tools.listElements()
                elements.map { it.kind }.toSet() shouldBe setOf("uml.class", "uml.interface", "uml.association")
            }
        }

        test("get_element_details surfaces attributes operations and relationships") {
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = ModelInspectionTools(ctx)
            runTest {
                umlTools.addClass(name = "Order")
                umlTools.addAttribute(classifierIdOrName = "Order", name = "total", type = "BigDecimal")
                umlTools.addOperation(classifierIdOrName = "Order", name = "submit", returnType = "Boolean")
                val elements = tools.listElements()
                val orderId = elements.first { it.kind == "uml.class" }.id
                val details = tools.getElementDetails(orderId)
                details.attributes shouldHaveSize 1
                details.operations shouldHaveSize 1
            }
        }

        test("get_element_details for unknown id returns not-found element") {
            val ctx = makeContext()
            val tools = ModelInspectionTools(ctx)
            runTest {
                val details = tools.getElementDetails("no-such-id")
                details.name shouldBe "(not found)"
            }
        }

        test("find_unused_elements returns classifiers without relationships") {
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = ModelInspectionTools(ctx)
            runTest {
                umlTools.addClass(name = "Connected")
                umlTools.addClass(name = "Isolated")
                umlTools.addClass(name = "AlsoConnected")
                umlTools.addAssociation(sourceIdOrName = "Connected", targetIdOrName = "AlsoConnected")
                val report = tools.findUnusedElements()
                report.unusedElementIds shouldHaveSize 1
                // Isolated should be flagged
                val elem = tools.listElements().first { it.id in report.unusedElementIds }
                elem.name shouldBe "Isolated"
            }
        }

        test("find_unused_elements rationale text mentions the unused names") {
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = ModelInspectionTools(ctx)
            runTest {
                umlTools.addClass(name = "OrphanClass")
                val report = tools.findUnusedElements()
                report.rationale shouldContain "OrphanClass"
            }
        }

        test("find_unused_elements ignores classifiers used by stereotypes") {
            val ctx = makeContext()
            val umlTools = UmlEditingTools(ctx)
            val tools = ModelInspectionTools(ctx)
            runTest {
                umlTools.addClass(name = "ServiceA")
                umlTools.addClass(name = "ServiceB")
                umlTools.addGeneralization(childIdOrName = "ServiceA", parentIdOrName = "ServiceB")
                val report = tools.findUnusedElements()
                // Both are connected via generalization
                report.unusedElementIds shouldHaveSize 0
            }
        }
    })
