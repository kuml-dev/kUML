package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SerializationTest : FunSpec(body = {

    val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    // ── UmlClass round-trip ───────────────────────────────────────────────────

    test(name = "UmlClass round-trips through JSON") {
        val before =
            UmlClass(
                id = "domain::Order",
                name = "Order",
                visibility = Visibility.PUBLIC,
                isAbstract = false,
                attributes =
                    listOf(
                        UmlProperty(
                            id = "domain::Order::id",
                            name = "id",
                            type = UmlTypeRef(name = "UUID"),
                            visibility = Visibility.PRIVATE,
                        ),
                    ),
                operations =
                    listOf(
                        UmlOperation(
                            id = "domain::Order::confirm()",
                            name = "confirm",
                            returnType = UmlTypeRef(name = "Unit"),
                        ),
                    ),
            )
        val text = json.encodeToString(before)
        val after = json.decodeFromString<UmlClass>(text)
        after shouldBe before
    }

    // ── UmlInterface round-trip ───────────────────────────────────────────────

    test(name = "UmlInterface round-trips through JSON") {
        val before =
            UmlInterface(
                id = "IOrderSvc",
                name = "IOrderSvc",
                operations =
                    listOf(
                        UmlOperation(id = "IOrderSvc::place()", name = "place"),
                    ),
            )
        val text = json.encodeToString(before)
        val after = json.decodeFromString<UmlInterface>(text)
        after shouldBe before
    }

    // ── UmlEnumeration round-trip ─────────────────────────────────────────────

    test(name = "UmlEnumeration round-trips through JSON") {
        val before =
            UmlEnumeration(
                id = "OrderStatus",
                name = "OrderStatus",
                literals =
                    listOf(
                        UmlEnumerationLiteral(id = "OrderStatus::DRAFT", name = "DRAFT"),
                        UmlEnumerationLiteral(id = "OrderStatus::CONFIRMED", name = "CONFIRMED"),
                    ),
            )
        val text = json.encodeToString(before)
        val after = json.decodeFromString<UmlEnumeration>(text)
        after shouldBe before
    }

    // ── UmlAssociation round-trip ─────────────────────────────────────────────

    test(name = "UmlAssociation round-trips through JSON") {
        val before =
            UmlAssociation(
                id = "assoc::Order-->Item",
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = "Order", multiplicity = Multiplicity(lower = 1, upper = 1)),
                        UmlAssociationEnd(typeId = "Item", multiplicity = Multiplicity(lower = 1, upper = null)),
                    ),
                aggregation = AggregationKind.COMPOSITE,
            )
        val text = json.encodeToString(before)
        val after = json.decodeFromString<UmlAssociation>(text)
        after shouldBe before
    }

    // ── UmlStateMachine round-trip ────────────────────────────────────────────

    test(name = "UmlStateMachine round-trips through JSON") {
        val before =
            UmlStateMachine(
                id = "OrderSM",
                name = "OrderSM",
                vertices =
                    listOf(
                        UmlPseudostate(id = "OrderSM::__init", name = "__init", kind = PseudostateKind.INITIAL),
                        UmlState(id = "OrderSM::DRAFT", name = "DRAFT"),
                        UmlFinalState(id = "OrderSM::DELIVERED", name = "DELIVERED"),
                    ),
                transitions =
                    listOf(
                        UmlTransition(
                            id = "OrderSM::t::DRAFT->CONFIRMED",
                            sourceId = "OrderSM::DRAFT",
                            targetId = "OrderSM::CONFIRMED",
                            trigger = "confirm()",
                            guard = "[payment.success]",
                        ),
                    ),
            )
        val text = json.encodeToString(before)
        val after = json.decodeFromString<UmlStateMachine>(text)
        after shouldBe before
    }

    // ── UmlInteraction round-trip ─────────────────────────────────────────────

    test(name = "UmlInteraction round-trips through JSON") {
        val before =
            UmlInteraction(
                id = "PlaceOrder",
                name = "PlaceOrder",
                lifelines =
                    listOf(
                        UmlLifeline(id = "PlaceOrder::ll::Customer", name = "Customer", isActor = true),
                    ),
                messages =
                    listOf(
                        UmlMessage(
                            id = "PlaceOrder::msg::1",
                            label = "createOrder()",
                            fromLifelineId = "PlaceOrder::ll::Customer",
                            toLifelineId = "PlaceOrder::ll::OrderSvc",
                            sequence = 1,
                        ),
                    ),
            )
        val text = json.encodeToString(before)
        val after = json.decodeFromString<UmlInteraction>(text)
        after shouldBe before
    }

    // ── UmlComponent round-trip ───────────────────────────────────────────────

    test(name = "UmlComponent round-trips through JSON") {
        val before =
            UmlComponent(
                id = "OrderSvc",
                name = "OrderSvc",
                ports = listOf(UmlPort(id = "OrderSvc::p", name = "p")),
                providedInterfaceIds = listOf("IOrderSvc"),
            )
        val text = json.encodeToString(before)
        val after = json.decodeFromString<UmlComponent>(text)
        after shouldBe before
    }

    // ── Polymorphic List<UmlElement> round-trip ───────────────────────────────

    test(name = "polymorphic List<UmlElement> round-trips with type discriminator") {
        val elements: List<UmlElement> =
            listOf(
                UmlClass(id = "Order", name = "Order"),
                UmlInterface(id = "IRepo", name = "IRepo"),
                UmlEnumeration(id = "Status", name = "Status"),
                UmlGeneralization(id = "gen::Dog-|>Animal", specificId = "Dog", generalId = "Animal"),
                UmlActor(id = "Customer", name = "Customer"),
                UmlUseCase(id = "PlaceOrder", name = "Place Order"),
            )

        val text = json.encodeToString(elements)
        val after = json.decodeFromString<List<UmlElement>>(text)
        after shouldBe elements
    }

    test(name = "JSON for polymorphic UmlElement contains type discriminator") {
        val elements: List<UmlElement> =
            listOf(
                UmlClass(id = "A", name = "A"),
                UmlInterface(id = "B", name = "B"),
            )
        val text = json.encodeToString(elements)
        text shouldContain "type"
    }

    // ── KumlMetaValue in metadata ─────────────────────────────────────────────

    test(name = "metadata with KumlMetaValue round-trips through JSON") {
        val before =
            UmlClass(
                id = "Annotated",
                name = "Annotated",
                metadata =
                    mapOf(
                        "line" to KumlMetaValue.Integer(value = 42L),
                        "source" to KumlMetaValue.Text(value = "model.kuml.kts"),
                        "stable" to KumlMetaValue.Flag(value = true),
                    ),
            )
        val text = json.encodeToString(before)
        val after = json.decodeFromString<UmlClass>(text)
        after shouldBe before
        (after.metadata["line"] as KumlMetaValue.Integer).value shouldBe 42L
        (after.metadata["source"] as KumlMetaValue.Text).value shouldBe "model.kuml.kts"
    }

    // ── Multiplicity round-trip ───────────────────────────────────────────────

    test(name = "unbounded multiplicity round-trips with null upper") {
        val before = Multiplicity(lower = 1, upper = null)
        val text = json.encodeToString(before)
        val after = json.decodeFromString<Multiplicity>(text)
        after shouldBe before
        after.upper shouldBe null
    }

    // ── UmlPackage round-trip ─────────────────────────────────────────────────

    test(name = "UmlPackage with nested members round-trips through JSON") {
        val before =
            UmlPackage(
                id = "domain",
                name = "domain",
                members =
                    listOf(
                        UmlClass(id = "domain::Order", name = "Order"),
                        UmlEnumeration(id = "domain::Status", name = "Status"),
                    ),
            )
        val text = json.encodeToString(before)
        val after = json.decodeFromString<UmlPackage>(text)
        after shouldBe before
    }
})
