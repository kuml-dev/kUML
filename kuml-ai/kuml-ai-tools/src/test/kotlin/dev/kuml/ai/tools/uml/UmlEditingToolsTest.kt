package dev.kuml.ai.tools.uml

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.ai.tools.result.RemoveResult
import dev.kuml.ai.tools.result.RenameResult
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInterface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class UmlEditingToolsTest :
    FunSpec({

        fun makeTools(): Pair<AgentEditingContext, UmlEditingTools> {
            val ctx = AgentEditingContext.emptyUml()
            return ctx to UmlEditingTools(ctx)
        }

        test("add_class appends a UmlClass and emits AddElement patch") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addClass("OrderService")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                model.elements shouldHaveSize 1
                (model.elements[0] as UmlClass).name shouldBe "OrderService"
                ctx.patches() shouldHaveSize 1
            }
        }

        test("add_class with explicit stereotype attaches it to the class") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("UserRepo", stereotype = "repository")
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val cls = model.elements[0] as UmlClass
                cls.stereotypes shouldBe listOf("repository")
            }
        }

        test("add_class with attributes inlines them in declaration order") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass(
                    "Product",
                    attributes =
                        listOf(
                            UmlEditingTools.AttributeSpec("name", "String"),
                            UmlEditingTools.AttributeSpec("price", "BigDecimal"),
                        ),
                )
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val cls = model.elements[0] as UmlClass
                cls.attributes shouldHaveSize 2
                cls.attributes[0].name shouldBe "name"
                cls.attributes[1].name shouldBe "price"
            }
        }

        test("add_class twice with the same name disambiguates ids") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("Service")
                tools.addClass("Service")
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                model.elements shouldHaveSize 2
                model.elements.map { it.id }.toSet() shouldHaveSize 2
            }
        }

        test("add_interface appends a UmlInterface") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addInterface("PaymentProcessor")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                model.elements[0].shouldBeInstanceOf<UmlInterface>()
            }
        }

        test("add_attribute against unknown classifier returns Failure with hint") {
            val (_, tools) = makeTools()
            runTest {
                val result = tools.addAttribute("NonExistent", "id", "String")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
                (result as PatchApplyResult.Failure).hint.shouldBe("Use list_elements to discover available classifier ids")
            }
        }

        test("add_attribute with valid PRIVATE visibility code works") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("Order")
                val result = tools.addAttribute("Order", "total", "BigDecimal", visibility = "PRIVATE")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val cls = (ctx.resolveModel() as AnyKumlModel.Uml).elements[0] as UmlClass
                cls.attributes shouldHaveSize 1
                cls.attributes[0].visibility shouldBe dev.kuml.uml.Visibility.PRIVATE
            }
        }

        test("add_attribute with invalid visibility code returns Failure") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("Order")
                val result = tools.addAttribute("Order", "total", "BigDecimal", visibility = "BANANA")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
            }
        }

        test("add_operation parses parameter list correctly") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("OrderService")
                tools.addOperation(
                    "OrderService",
                    "submitOrder",
                    parameters = listOf("order: Order", "discount: BigDecimal"),
                    returnType = "Receipt",
                )
                val cls = (ctx.resolveModel() as AnyKumlModel.Uml).elements[0] as UmlClass
                cls.operations shouldHaveSize 1
                cls.operations[0].parameters shouldHaveSize 2
            }
        }

        test("add_association links source and target by name") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("Order")
                tools.addClass("Customer")
                val result = tools.addAssociation("Order", "Customer")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                (ctx.resolveModel() as AnyKumlModel.Uml).relationships shouldHaveSize 1
            }
        }

        test("add_association links source and target by id") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("Prod")
                tools.addClass("Cat")
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val prodId = model.elements[0].id
                val catId = model.elements[1].id
                val result = tools.addAssociation(prodId, catId)
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
            }
        }

        test("add_generalization rejects self-loop with hint") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("Animal")
                val result = tools.addGeneralization("Animal", "Animal")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
                (result as PatchApplyResult.Failure).hint.shouldBe("Child and parent must be different classifiers")
            }
        }

        test("add_generalization records AddRelationship patch") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("Dog")
                tools.addClass("Animal")
                tools.addGeneralization("Dog", "Animal")
                ctx.patches().last().shouldBeInstanceOf<dev.kuml.ai.tools.context.ModelPatch.AddRelationship>()
            }
        }

        test("remove_element returns Success and patch is recorded") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("Temp")
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val id = model.elements[0].id
                val result = tools.removeElement(id)
                result.shouldBeInstanceOf<RemoveResult.Success>()
                (ctx.resolveModel() as AnyKumlModel.Uml).elements shouldHaveSize 0
            }
        }

        test("remove_element with unknown id returns Failure without patch") {
            val (ctx, tools) = makeTools()
            runTest {
                val before = ctx.patches().size
                val result = tools.removeElement("not-exists")
                result.shouldBeInstanceOf<RemoveResult.Failure>()
                ctx.patches().size shouldBe before
            }
        }

        test("rename_element preserves the element id") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass("OldName")
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val id = model.elements[0].id
                val result = tools.renameElement(id, "NewName")
                result.shouldBeInstanceOf<RenameResult.Success>()
                val after = ctx.resolveModel() as AnyKumlModel.Uml
                after.elements[0].id shouldBe id // id preserved
                after.elements[0].name shouldBe "NewName"
            }
        }

        test("set_current_diagram switches subsequent tool calls") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.setCurrentDiagram("diagram-2")
                ctx.currentDiagramId shouldBe "diagram-2"
            }
        }

        test("parallel tool calls on same context do not corrupt id namespace") {
            val (ctx, tools) = makeTools()
            runTest {
                coroutineScope {
                    repeat(100) { n ->
                        launch {
                            tools.addClass("Class$n")
                        }
                    }
                }
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                model.elements shouldHaveSize 100
                // All IDs must be unique
                model.elements
                    .map { it.id }
                    .toSet()
                    .size shouldBe 100
            }
        }
    })
