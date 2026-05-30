package dev.kuml.uml.dsl

import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class AssociationBuilderTest : FunSpec({

    // ── By string IDs ──────────────────────────────────────────────────────────

    test("association by string ids creates UmlAssociation") {
        val model =
            umlModel("M") {
                classOf("Order")
                classOf("Item")
                association(sourceId = "Order", targetId = "Item")
            }
        model.elements.filterIsInstance<UmlAssociation>() shouldHaveSize 1
    }

    test("association by string ids has correct end type ids") {
        val model =
            umlModel("M") {
                association(sourceId = "Order", targetId = "Item")
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.ends[0].typeId shouldBe "Order"
        assoc.ends[1].typeId shouldBe "Item"
    }

    test("association id format is assoc::source-->target") {
        val model =
            umlModel("M") {
                association(sourceId = "Order", targetId = "Item")
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.id shouldBe "assoc::Order-->Item"
    }

    test("named association id includes name segment") {
        val model =
            umlModel("M") {
                association(sourceId = "Order", targetId = "Item") { name = "contains" }
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.id shouldBe "assoc::Order-->Item::contains"
    }

    // ── By classifier handles ──────────────────────────────────────────────────

    test("association by classifier handles uses handle ids") {
        val model =
            umlModel("M") {
                val order = classOf("Order")
                val item = classOf("Item")
                association(source = order, target = item)
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.ends[0].typeId shouldBe "Order"
        assoc.ends[1].typeId shouldBe "Item"
    }

    test("association by handle from enum uses enum id as typeId") {
        val model =
            umlModel("M") {
                val status = enumOf("OrderStatus") { literal("DRAFT") }
                val order = classOf("Order") {}
                association(source = order, target = status)
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.ends[1].typeId shouldBe "OrderStatus"
    }

    // ── Aggregation ────────────────────────────────────────────────────────────

    test("association default aggregation is NONE") {
        val model =
            umlModel("M") {
                association(sourceId = "A", targetId = "B")
            }
        model.elements.filterIsInstance<UmlAssociation>().first().aggregation shouldBe AggregationKind.NONE
    }

    test("association aggregation COMPOSITE is stored") {
        val model =
            umlModel("M") {
                association(sourceId = "Order", targetId = "Item") {
                    aggregation = AggregationKind.COMPOSITE
                }
            }
        model.elements.filterIsInstance<UmlAssociation>().first().aggregation shouldBe AggregationKind.COMPOSITE
    }

    test("association aggregation SHARED is stored") {
        val model =
            umlModel("M") {
                association(sourceId = "Team", targetId = "Member") {
                    aggregation = AggregationKind.SHARED
                }
            }
        model.elements.filterIsInstance<UmlAssociation>().first().aggregation shouldBe AggregationKind.SHARED
    }

    // ── Multiplicity ───────────────────────────────────────────────────────────

    test("source end multiplicity string is parsed correctly") {
        val model =
            umlModel(name = "M") {
                association(sourceId = "Order", targetId = "Item") {
                    source { multiplicity(spec = "1") }
                }
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.ends[0].multiplicity shouldBe Multiplicity(lower = 1, upper = 1)
    }

    test("target end multiplicity 1..* is parsed correctly") {
        val model =
            umlModel(name = "M") {
                association(sourceId = "Order", targetId = "Item") {
                    target { multiplicity(spec = "1..*") }
                }
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.ends[1].multiplicity shouldBe Multiplicity(lower = 1, upper = null)
    }

    test("target end multiplicity 0..* is parsed correctly") {
        val model =
            umlModel(name = "M") {
                association(sourceId = "Customer", targetId = "Order") {
                    target { multiplicity(spec = "0..*") }
                }
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.ends[1].multiplicity shouldBe Multiplicity(lower = 0, upper = null)
    }

    // ── Role ──────────────────────────────────────────────────────────────────

    test("source end role is stored") {
        val model =
            umlModel("M") {
                association(sourceId = "Order", targetId = "Item") {
                    target { role = "items" }
                }
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.ends[1].role shouldBe "items"
    }

    // ── Both ends default navigable ────────────────────────────────────────────

    test("association ends are navigable by default") {
        val model =
            umlModel("M") {
                association(sourceId = "A", targetId = "B")
            }
        val assoc = model.elements.filterIsInstance<UmlAssociation>().first()
        assoc.ends[0].navigable shouldBe true
        assoc.ends[1].navigable shouldBe true
    }
})

private val dev.kuml.core.model.KumlModel.elements
    get() = (root as dev.kuml.core.model.KumlDiagram).elements
