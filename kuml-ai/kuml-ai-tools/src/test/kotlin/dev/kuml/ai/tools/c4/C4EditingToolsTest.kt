package dev.kuml.ai.tools.c4

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class C4EditingToolsTest :
    FunSpec({

        fun makeTools(): Pair<AgentEditingContext, C4EditingTools> {
            val ctx = AgentEditingContext.emptyC4()
            return ctx to C4EditingTools(ctx)
        }

        test("add_person creates a Person and adds it to the C4 model") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addPerson("Customer", "End user of the system")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val model = (ctx.resolveModel() as AnyKumlModel.C4).model
                model.elements shouldHaveSize 1
                model.elements[0].shouldBeInstanceOf<C4Person>()
                (model.elements[0] as C4Person).name shouldBe "Customer"
            }
        }

        test("add_software_system marks external systems with dashed border flag") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addSoftwareSystem("Payment Gateway", isExternal = true)
                val model = (ctx.resolveModel() as AnyKumlModel.C4).model
                val sys = model.elements[0] as C4SoftwareSystem
                sys.external shouldBe true
            }
        }

        test("add_container requires existing software system parent") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addSoftwareSystem("MyApp")
                val result = tools.addContainer("MyApp", "Web API", technology = "Ktor")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
            }
        }

        test("add_container with unknown parent returns Failure") {
            val (_, tools) = makeTools()
            runTest {
                val result = tools.addContainer("NonExistentSystem", "Container")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
            }
        }

        test("add_component requires existing container parent") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addSoftwareSystem("App")
                tools.addContainer("App", "API")
                val result = tools.addComponent("API", "OrderController")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
            }
        }

        test("add_component records nested parent id") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addSoftwareSystem("App")
                tools.addContainer("App", "API")
                tools.addComponent("API", "OrderController")
                val model = (ctx.resolveModel() as AnyKumlModel.C4).model
                val component = model.elements.filterIsInstance<C4Component>().first()
                component.container.shouldBe(
                    model.elements
                        .filterIsInstance<C4Container>()
                        .first()
                        .id,
                )
            }
        }

        test("add_relationship attaches label and technology") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addPerson("User")
                tools.addSoftwareSystem("App")
                val result = tools.addRelationship("User", "App", "uses", technology = "HTTPS")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val model = (ctx.resolveModel() as AnyKumlModel.C4).model
                model.relationships shouldHaveSize 1
                model.relationships[0].label shouldBe "uses"
                model.relationships[0].technology shouldBe "HTTPS"
            }
        }

        test("add_relationship between cross-parent elements works") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addSoftwareSystem("SystemA")
                tools.addSoftwareSystem("SystemB", isExternal = true)
                val result = tools.addRelationship("SystemA", "SystemB", "sends data to")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
            }
        }

        test("currentDiagramId defaults to agent-default-context-diagram on first add") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addPerson("Customer")
                ctx.currentDiagramId shouldBe "agent-default-context-diagram"
            }
        }

        test("bulk add of 20 elements all record patches") {
            val (ctx, tools) = makeTools()
            runTest {
                repeat(20) { n ->
                    tools.addPerson("Person$n")
                }
                ctx.patches() shouldHaveSize 20
                (ctx.resolveModel() as AnyKumlModel.C4).model.elements shouldHaveSize 20
            }
        }
    })
