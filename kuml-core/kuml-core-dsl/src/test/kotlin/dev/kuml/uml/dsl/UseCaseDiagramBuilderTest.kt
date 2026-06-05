package dev.kuml.uml.dsl

import dev.kuml.core.dsl.useCaseDiagram
import dev.kuml.core.model.ActorStyle
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.UseCaseDiagramConfig
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlUseCase
import dev.kuml.uml.UmlUseCaseSubject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class UseCaseDiagramBuilderTest :
    FunSpec(body = {

        test(name = "empty use-case diagram builds without error") {
            val d = useCaseDiagram(name = "Empty") {}
            d.name shouldBe "Empty"
            d.type shouldBe DiagramType.USE_CASE
            d.elements.shouldHaveSize(0)
        }

        test(name = "actor is added with deterministic id") {
            val d = useCaseDiagram(name = "Checkout") { actor(name = "Customer") }
            val actors = d.elements.filterIsInstance<UmlActor>()
            actors shouldHaveSize 1
            actors.single().id shouldBe "Customer"
            actors.single().name shouldBe "Customer"
        }

        test(name = "useCase is added with deterministic id") {
            val d = useCaseDiagram(name = "Checkout") { useCase(name = "Place Order") }
            val useCases = d.elements.filterIsInstance<UmlUseCase>()
            useCases shouldHaveSize 1
            useCases.single().id shouldBe "Place Order"
            useCases.single().name shouldBe "Place Order"
        }

        test(name = "subject contains the listed use cases") {
            val d =
                useCaseDiagram(name = "Checkout") {
                    val a = useCase(name = "A")
                    val b = useCase(name = "B")
                    subject(name = "Shop", containedUseCases = arrayOf(a, b))
                }
            val subjects = d.elements.filterIsInstance<UmlUseCaseSubject>()
            subjects shouldHaveSize 1
            val s = subjects.single()
            s.useCaseIds shouldBe listOf("A", "B")
        }

        test(name = "include relationship uses UmlIds-include format") {
            val d =
                useCaseDiagram(name = "Checkout") {
                    val a = useCase(name = "Place Order")
                    val b = useCase(name = "Validate Cart")
                    include(base = a, addition = b)
                }
            val rels = d.elements.filterIsInstance<UmlInclude>()
            rels shouldHaveSize 1
            rels.single().id shouldBe "include::Place Order..>Validate Cart"
            rels.single().baseId shouldBe "Place Order"
            rels.single().additionId shouldBe "Validate Cart"
        }

        test(name = "extend with extensionPoint stores extensionPoint") {
            val d =
                useCaseDiagram(name = "Checkout") {
                    val place = useCase(name = "Place Order")
                    val discount = useCase(name = "Apply Discount")
                    extend(base = place, extension = discount, at = "PaymentChosen")
                }
            val rels = d.elements.filterIsInstance<UmlExtend>()
            rels shouldHaveSize 1
            rels.single().extensionPoint shouldBe "PaymentChosen"
            rels.single().baseId shouldBe "Place Order"
            rels.single().extensionId shouldBe "Apply Discount"
        }

        test(name = "actor to useCase association is accepted") {
            val d =
                useCaseDiagram(name = "Checkout") {
                    val customer = actor(name = "Customer")
                    val place = useCase(name = "Place Order")
                    association(source = customer, target = place)
                }
            d.elements.filterIsInstance<UmlAssociation>() shouldHaveSize 1
        }

        test(name = "adding UmlClass to use-case diagram throws") {
            val cls = UmlClass(id = "X", name = "X")
            val builder = UseCaseDiagramBuilder(name = "Bad")
            shouldThrow<IllegalArgumentException> {
                builder.addNamedElement(cls)
            }
        }

        test(name = "adding UmlDependency to use-case diagram throws") {
            val dep = UmlDependency(id = "dep::A..>B", clientId = "A", supplierId = "B")
            val builder = UseCaseDiagramBuilder(name = "Bad")
            shouldThrow<IllegalArgumentException> {
                builder.addRelationship(dep)
            }
        }

        test(name = "config is UseCaseDiagramConfig with defaults") {
            val d = useCaseDiagram("Config Test") {}
            val config = d.config
            config.shouldBeInstanceOf<UseCaseDiagramConfig>()
            config.showSubjectBox shouldBe true
            config.actorStyle shouldBe ActorStyle.STICK_FIGURE
        }
    })
