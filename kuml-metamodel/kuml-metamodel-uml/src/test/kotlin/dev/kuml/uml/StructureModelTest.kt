package dev.kuml.uml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StructureModelTest :
    FunSpec(body = {

        // ── UmlComponent ──────────────────────────────────────────────────────────

        test(name = "minimal component builds with required fields only") {
            val comp = UmlComponent(id = "PaymentSvc", name = "PaymentSvc")
            comp.ports.shouldBeEmpty()
            comp.providedInterfaceIds.shouldBeEmpty()
            comp.requiredInterfaceIds.shouldBeEmpty()
            comp.nestedComponents.shouldBeEmpty()
            comp.isAbstract shouldBe false
            comp.shouldBeInstanceOf<UmlClassifier>()
        }

        test(name = "component stores provided and required interfaces by ID") {
            val comp =
                UmlComponent(
                    id = "OrderSvc",
                    name = "OrderSvc",
                    providedInterfaceIds = listOf("IOrderSvc"),
                    requiredInterfaceIds = listOf("IPaymentSvc", "IEmailSvc"),
                )
            comp.providedInterfaceIds shouldHaveSize 1
            comp.providedInterfaceIds[0] shouldBe "IOrderSvc"
            comp.requiredInterfaceIds shouldHaveSize 2
        }

        test(name = "component holds ports") {
            val port =
                UmlPort(
                    id = "OrderSvc::orderPort",
                    name = "orderPort",
                    type = UmlTypeRef(name = "IOrderSvc"),
                )
            val comp =
                UmlComponent(
                    id = "OrderSvc",
                    name = "OrderSvc",
                    ports = listOf(port),
                )
            comp.ports shouldHaveSize 1
            comp.ports[0].type?.name shouldBe "IOrderSvc"
        }

        test(name = "conjugated port is modelled with isConjugated = true") {
            val port = UmlPort(id = "C::p", name = "p", isConjugated = true)
            port.isConjugated shouldBe true
        }

        test(name = "component can be nested") {
            val inner = UmlComponent(id = "System::API", name = "API")
            val outer =
                UmlComponent(
                    id = "System",
                    name = "System",
                    nestedComponents = listOf(inner),
                )
            outer.nestedComponents shouldHaveSize 1
            outer.nestedComponents[0].name shouldBe "API"
        }

        // ── UmlConnector ──────────────────────────────────────────────────────────

        test(name = "connector links two ports by ID") {
            val conn =
                UmlConnector(
                    id = "conn::System::API::orderPort--System::DB::dbPort",
                    end1Id = "System::API::orderPort",
                    end2Id = "System::DB::dbPort",
                    name = "orderLink",
                )
            conn.end1Id shouldBe "System::API::orderPort"
            conn.end2Id shouldBe "System::DB::dbPort"
            conn.name shouldBe "orderLink"
            conn.shouldBeInstanceOf<UmlRelationship>()
        }

        // ── UmlActor / UmlUseCase ─────────────────────────────────────────────────

        test(name = "actor builds and is a UmlClassifier") {
            val actor = UmlActor(id = "Customer", name = "Customer")
            actor.name shouldBe "Customer"
            actor.shouldBeInstanceOf<UmlClassifier>()
        }

        test(name = "use case builds and is a UmlClassifier") {
            val uc = UmlUseCase(id = "PlaceOrder", name = "Place Order")
            uc.name shouldBe "Place Order"
            uc.shouldBeInstanceOf<UmlClassifier>()
        }

        test(name = "use case subject holds use-case IDs") {
            val subject =
                UmlUseCaseSubject(
                    id = "OnlineShop",
                    name = "Online Shop",
                    useCaseIds = listOf("PlaceOrder", "CancelOrder", "TrackShipment"),
                )
            subject.useCaseIds shouldHaveSize 3
            subject.shouldBeInstanceOf<UmlNamedElement>()
        }

        // ── UmlInclude / UmlExtend ────────────────────────────────────────────────

        test(name = "include stores base and addition IDs") {
            val inc =
                UmlInclude(
                    id = "include::Checkout..>ValidateCart",
                    baseId = "Checkout",
                    additionId = "ValidateCart",
                )
            inc.baseId shouldBe "Checkout"
            inc.additionId shouldBe "ValidateCart"
            inc.shouldBeInstanceOf<UmlRelationship>()
        }

        test(name = "extend stores base, extension and optional extension point") {
            val ext =
                UmlExtend(
                    id = "extend::Checkout..>ApplyDiscount",
                    baseId = "Checkout",
                    extensionId = "ApplyDiscount",
                    extensionPoint = "afterValidation",
                )
            ext.baseId shouldBe "Checkout"
            ext.extensionId shouldBe "ApplyDiscount"
            ext.extensionPoint shouldBe "afterValidation"
            ext.shouldBeInstanceOf<UmlRelationship>()
        }

        test(name = "extend without extension point defaults to null") {
            val ext = UmlExtend(id = "x", baseId = "A", extensionId = "B")
            ext.extensionPoint shouldBe null
        }

        // ── Visibility ────────────────────────────────────────────────────────────

        test(name = "Visibility has exactly PUBLIC, PRIVATE, PROTECTED, PACKAGE") {
            Visibility.entries.map { it.name } shouldBe
                listOf("PUBLIC", "PRIVATE", "PROTECTED", "PACKAGE")
        }
    })
