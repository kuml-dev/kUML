package dev.kuml.uml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RelationshipModelTest : FunSpec(body = {

    // ── UmlAssociation ────────────────────────────────────────────────────────

    test(name = "association between two classes stores both ends") {
        val ends =
            listOf(
                UmlAssociationEnd(typeId = "domain::Order", role = "order", multiplicity = Multiplicity(lower = 1, upper = 1)),
                UmlAssociationEnd(typeId = "domain::OrderItem", role = "items", multiplicity = Multiplicity(lower = 1, upper = null)),
            )
        val assoc =
            UmlAssociation(
                id = "assoc::domain::Order-->domain::OrderItem",
                ends = ends,
            )
        assoc.ends shouldHaveSize 2
        assoc.ends[0].typeId shouldBe "domain::Order"
        assoc.ends[1].typeId shouldBe "domain::OrderItem"
        assoc.ends[1].multiplicity.upper shouldBe null
        assoc.aggregation shouldBe AggregationKind.NONE
    }

    test(name = "composition is modelled via aggregation = COMPOSITE") {
        val assoc =
            UmlAssociation(
                id = "assoc::Order-->Items",
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = "Order"),
                        UmlAssociationEnd(typeId = "OrderItem"),
                    ),
                aggregation = AggregationKind.COMPOSITE,
            )
        assoc.aggregation shouldBe AggregationKind.COMPOSITE
    }

    test(name = "shared aggregation is modelled via aggregation = SHARED") {
        val assoc =
            UmlAssociation(
                id = "assoc::Team-->Member",
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = "Team"),
                        UmlAssociationEnd(typeId = "Member"),
                    ),
                aggregation = AggregationKind.SHARED,
            )
        assoc.aggregation shouldBe AggregationKind.SHARED
    }

    test(name = "named association stores name") {
        val assoc =
            UmlAssociation(
                id = "assoc::Customer-->Order::places",
                name = "places",
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = "Customer", multiplicity = Multiplicity(lower = 1, upper = 1)),
                        UmlAssociationEnd(typeId = "Order", multiplicity = Multiplicity(lower = 0, upper = null)),
                    ),
            )
        assoc.name shouldBe "places"
    }

    test(name = "association is a UmlRelationship and UmlElement") {
        val assoc =
            UmlAssociation(
                id = "x",
                ends = listOf(UmlAssociationEnd(typeId = "A"), UmlAssociationEnd(typeId = "B")),
            )
        assoc.shouldBeInstanceOf<UmlRelationship>()
        assoc.shouldBeInstanceOf<UmlElement>()
    }

    // ── UmlGeneralization ─────────────────────────────────────────────────────

    test(name = "generalization stores specific and general IDs") {
        val gen =
            UmlGeneralization(
                id = "gen::Dog-|>Animal",
                specificId = "Dog",
                generalId = "Animal",
            )
        gen.specificId shouldBe "Dog"
        gen.generalId shouldBe "Animal"
        gen.shouldBeInstanceOf<UmlRelationship>()
    }

    // ── UmlInterfaceRealization ───────────────────────────────────────────────

    test(name = "interface realization stores implementing and interface IDs") {
        val real =
            UmlInterfaceRealization(
                id = "real::OrderSvc..|>IOrderSvc",
                implementingId = "OrderSvc",
                interfaceId = "IOrderSvc",
            )
        real.implementingId shouldBe "OrderSvc"
        real.interfaceId shouldBe "IOrderSvc"
        real.shouldBeInstanceOf<UmlRelationship>()
    }

    // ── UmlDependency ─────────────────────────────────────────────────────────

    test(name = "dependency stores client and supplier IDs") {
        val dep =
            UmlDependency(
                id = "dep::Order..>OrderStatus",
                clientId = "Order",
                supplierId = "OrderStatus",
            )
        dep.clientId shouldBe "Order"
        dep.supplierId shouldBe "OrderStatus"
        dep.shouldBeInstanceOf<UmlRelationship>()
    }

    test(name = "named dependency stores label") {
        val dep =
            UmlDependency(
                id = "dep::Service..>Repo",
                clientId = "Service",
                supplierId = "Repo",
                name = "<<use>>",
            )
        dep.name shouldBe "<<use>>"
    }

    // ── Multiplicity ──────────────────────────────────────────────────────────

    test(name = "multiplicity defaults to exactly one") {
        val m = Multiplicity()
        m.lower shouldBe 1
        m.upper shouldBe 1
    }

    test(name = "unbounded multiplicity uses null upper") {
        val m = Multiplicity(lower = 0, upper = null)
        m.lower shouldBe 0
        m.upper shouldBe null
    }

    test(name = "AggregationKind has exactly NONE, SHARED, COMPOSITE") {
        AggregationKind.entries.map { it.name } shouldBe listOf("NONE", "SHARED", "COMPOSITE")
    }
})
