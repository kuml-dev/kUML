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

class UseCaseDiagramBuilderTest : FunSpec({

    test("empty use-case diagram builds without error") {
        val d = useCaseDiagram("Empty") {}
        d.name shouldBe "Empty"
        d.type shouldBe DiagramType.USE_CASE
        d.elements.shouldHaveSize(0)
    }

    test("actor is added with deterministic id") {
        val d = useCaseDiagram("Checkout") { actor("Customer") }
        val actors = d.elements.filterIsInstance<UmlActor>()
        actors shouldHaveSize 1
        actors.single().id shouldBe "Customer"
        actors.single().name shouldBe "Customer"
    }

    test("useCase is added with deterministic id") {
        val d = useCaseDiagram("Checkout") { useCase("Place Order") }
        val useCases = d.elements.filterIsInstance<UmlUseCase>()
        useCases shouldHaveSize 1
        useCases.single().id shouldBe "Place Order"
        useCases.single().name shouldBe "Place Order"
    }

    test("subject contains the listed use cases") {
        val d = useCaseDiagram("Checkout") {
            val a = useCase("A")
            val b = useCase("B")
            subject("Shop", a, b)
        }
        val subjects = d.elements.filterIsInstance<UmlUseCaseSubject>()
        subjects shouldHaveSize 1
        val s = subjects.single()
        s.useCaseIds shouldBe listOf("A", "B")
    }

    test("include relationship uses UmlIds-include format") {
        val d = useCaseDiagram("Checkout") {
            val a = useCase("Place Order")
            val b = useCase("Validate Cart")
            include(base = a, addition = b)
        }
        val rels = d.elements.filterIsInstance<UmlInclude>()
        rels shouldHaveSize 1
        rels.single().id shouldBe "include::Place Order..>Validate Cart"
        rels.single().baseId shouldBe "Place Order"
        rels.single().additionId shouldBe "Validate Cart"
    }

    test("extend with extensionPoint stores extensionPoint") {
        val d = useCaseDiagram("Checkout") {
            val place = useCase("Place Order")
            val discount = useCase("Apply Discount")
            extend(base = place, extension = discount, at = "PaymentChosen")
        }
        val rels = d.elements.filterIsInstance<UmlExtend>()
        rels shouldHaveSize 1
        rels.single().extensionPoint shouldBe "PaymentChosen"
        rels.single().baseId shouldBe "Place Order"
        rels.single().extensionId shouldBe "Apply Discount"
    }

    test("actor to useCase association is accepted") {
        val d = useCaseDiagram("Checkout") {
            val customer = actor("Customer")
            val place = useCase("Place Order")
            association(source = customer, target = place)
        }
        d.elements.filterIsInstance<UmlAssociation>() shouldHaveSize 1
    }

    test("adding UmlClass to use-case diagram throws") {
        val cls = UmlClass(id = "X", name = "X")
        val builder = UseCaseDiagramBuilder(name = "Bad")
        shouldThrow<IllegalArgumentException> {
            builder.addNamedElement(cls)
        }
    }

    test("adding UmlDependency to use-case diagram throws") {
        val dep = UmlDependency(id = "dep::A..>B", clientId = "A", supplierId = "B")
        val builder = UseCaseDiagramBuilder(name = "Bad")
        shouldThrow<IllegalArgumentException> {
            builder.addRelationship(dep)
        }
    }

    test("config is UseCaseDiagramConfig with defaults") {
        val d = useCaseDiagram("Config Test") {}
        val config = d.config
        config.shouldBeInstanceOf<UseCaseDiagramConfig>()
        config.showSubjectBox shouldBe true
        config.actorStyle shouldBe ActorStyle.STICK_FIGURE
    }
})
