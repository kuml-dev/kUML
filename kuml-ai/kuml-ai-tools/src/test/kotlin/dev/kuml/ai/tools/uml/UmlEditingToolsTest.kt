package dev.kuml.ai.tools.uml

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.ai.tools.result.RemoveResult
import dev.kuml.ai.tools.result.RenameResult
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.multiplicityLabel
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
                val result = tools.addClass(name = "OrderService")
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
                tools.addClass(name = "UserRepo", stereotype = "repository")
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val cls = model.elements[0] as UmlClass
                cls.stereotypes shouldBe listOf("repository")
            }
        }

        test("add_class with attributes inlines them in declaration order") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass(
                    name = "Product",
                    attributes =
                        listOf(
                            UmlEditingTools.AttributeSpec(name = "name", type = "String"),
                            UmlEditingTools.AttributeSpec(name = "price", type = "BigDecimal"),
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
                tools.addClass(name = "Service")
                tools.addClass(name = "Service")
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                model.elements shouldHaveSize 2
                model.elements.map { it.id }.toSet() shouldHaveSize 2
            }
        }

        test("add_interface appends a UmlInterface") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addInterface(name = "PaymentProcessor")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                model.elements[0].shouldBeInstanceOf<UmlInterface>()
            }
        }

        test("add_attribute against unknown classifier returns Failure with hint") {
            val (_, tools) = makeTools()
            runTest {
                val result = tools.addAttribute(classifierIdOrName = "NonExistent", name = "id", type = "String")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
                result.hint.shouldBe("Use list_elements to discover available classifier ids")
            }
        }

        test("add_attribute with valid PRIVATE visibility code works") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass(name = "Order")
                val result = tools.addAttribute(classifierIdOrName = "Order", name = "total", type = "BigDecimal", visibility = "PRIVATE")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val cls = (ctx.resolveModel() as AnyKumlModel.Uml).elements[0] as UmlClass
                cls.attributes shouldHaveSize 1
                cls.attributes[0].visibility shouldBe dev.kuml.uml.Visibility.PRIVATE
            }
        }

        test("add_attribute with invalid visibility code returns Failure") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass(name = "Order")
                val result = tools.addAttribute(classifierIdOrName = "Order", name = "total", type = "BigDecimal", visibility = "BANANA")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
            }
        }

        test("add_operation parses parameter list correctly") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass(name = "OrderService")
                tools.addOperation(
                    classifierIdOrName = "OrderService",
                    name = "submitOrder",
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
                tools.addClass(name = "Order")
                tools.addClass(name = "Customer")
                val result = tools.addAssociation(sourceIdOrName = "Order", targetIdOrName = "Customer")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                (ctx.resolveModel() as AnyKumlModel.Uml).relationships shouldHaveSize 1
            }
        }

        test("add_association links source and target by id") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass(name = "Prod")
                tools.addClass(name = "Cat")
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val prodId = model.elements[0].id
                val catId = model.elements[1].id
                val result = tools.addAssociation(sourceIdOrName = prodId, targetIdOrName = catId)
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
            }
        }

        test("add_generalization rejects self-loop with hint") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass(name = "Animal")
                val result = tools.addGeneralization(childIdOrName = "Animal", parentIdOrName = "Animal")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
                result.hint.shouldBe("Child and parent must be different classifiers")
            }
        }

        test("add_generalization records AddRelationship patch") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass(name = "Dog")
                tools.addClass(name = "Animal")
                tools.addGeneralization(childIdOrName = "Dog", parentIdOrName = "Animal")
                ctx.patches().last().shouldBeInstanceOf<dev.kuml.ai.tools.context.ModelPatch.AddRelationship>()
            }
        }

        test("remove_element returns Success and patch is recorded") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addClass(name = "Temp")
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
                tools.addClass(name = "OldName")
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                val id = model.elements[0].id
                val result = tools.renameElement(elementId = id, newName = "NewName")
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
                            tools.addClass(name = "Class$n")
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

        suspend fun assocEnds(
            src: String?,
            tgt: String?,
        ): List<dev.kuml.uml.UmlAssociationEnd> {
            val (ctx, tools) = makeTools()
            tools.addClass(name = "A")
            tools.addClass(name = "B")
            tools.addAssociation(sourceIdOrName = "A", targetIdOrName = "B", sourceMultiplicity = src, targetMultiplicity = tgt)
            val model = ctx.resolveModel() as AnyKumlModel.Uml
            return (model.relationships.single() as dev.kuml.uml.UmlAssociation).ends
        }

        test("add_association with target '1' draws an explicit 1 only on the target end") {
            runTest {
                val ends = assocEnds(src = null, tgt = "1")
                ends[1].multiplicityExplicit shouldBe true
                ends[1].multiplicityLabel() shouldBe "1"
                ends[0].multiplicityExplicit shouldBe false
                ends[0].multiplicityLabel() shouldBe null
            }
        }

        test("add_association with blank multiplicity stays unlabelled") {
            runTest {
                for (blank in listOf("  ", "")) {
                    val ends = assocEnds(src = blank, tgt = blank)
                    ends.map { it.multiplicityExplicit } shouldBe listOf(false, false)
                    ends.map { it.multiplicityLabel() } shouldBe listOf(null, null)
                }
            }
        }

        test("add_association with 0..* is labelled and without arguments unlabelled") {
            runTest {
                val ends = assocEnds(src = "0..*", tgt = null)
                ends[0].multiplicityExplicit shouldBe true
                ends[0].multiplicityLabel() shouldBe "0..*"
                val none = assocEnds(src = null, tgt = null)
                none.map { it.multiplicityLabel() } shouldBe listOf(null, null)
            }
        }

        test("explicit flag survives a JSON round trip of the association") {
            runTest {
                val (ctx, tools) = makeTools()
                tools.addClass(name = "A")
                tools.addClass(name = "B")
                tools.addAssociation(sourceIdOrName = "A", targetIdOrName = "B", targetMultiplicity = "1")
                val assoc = (ctx.resolveModel() as AnyKumlModel.Uml).relationships.single() as dev.kuml.uml.UmlAssociation
                val json =
                    kotlinx.serialization.json.Json
                        .encodeToString(
                            dev.kuml.uml.UmlAssociation
                                .serializer(),
                            assoc,
                        )
                (json.contains("multiplicityExplicit")) shouldBe true
                val back =
                    kotlinx.serialization.json.Json
                        .decodeFromString(
                            dev.kuml.uml.UmlAssociation
                                .serializer(),
                            json,
                        )
                back.ends[1].multiplicityLabel() shouldBe "1"
            }
        }
    })
