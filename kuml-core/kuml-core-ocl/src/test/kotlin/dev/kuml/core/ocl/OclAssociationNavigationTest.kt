package dev.kuml.core.ocl

import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun eval(
    self: Any,
    expr: String,
    model: List<Any>,
): Any? {
    val tokens = OclLexer.tokenize(expr)
    val ast = OclParser(tokens).parse()
    return OclEvaluator(self, model).eval(ast)
}

/**
 * V3.2.21 — Association-end navigation, opposite navigation and `closure()`
 * over the kUML metamodel (structural navigation between [UmlClass]
 * classifiers via [UmlAssociation], not runtime instance navigation).
 */
class OclAssociationNavigationTest :
    FunSpec({

        // ── Single-valued to-one navigation (role-named) ────────────────────
        test("navigates a to-one association end by role name") {
            val order = UmlClass(id = "Order", name = "Order")
            val customer = UmlClass(id = "Customer", name = "Customer")
            val assoc =
                UmlAssociation(
                    id = "Order_Customer",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order", role = "order", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "Customer", role = "customer", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val model = listOf(order, customer, assoc)
            val result = eval(order, "self.customer", model)
            (result as UmlClass).name shouldBe "Customer"
        }

        // ── Navigation by implicit (unnamed-role) classifier name ───────────
        test("navigates by referenced classifier name when role is unset") {
            val order = UmlClass(id = "Order", name = "Order")
            val customer = UmlClass(id = "Customer", name = "Customer")
            val assoc =
                UmlAssociation(
                    id = "Order_Customer",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "Customer", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val model = listOf(order, customer, assoc)
            val result = eval(order, "self.customer", model)
            (result as UmlClass).name shouldBe "Customer"
        }

        // ── To-many navigation ───────────────────────────────────────────────
        test("navigates a to-many association end as a List") {
            val order = UmlClass(id = "Order", name = "Order")
            val item = UmlClass(id = "OrderItem", name = "OrderItem")
            val assoc =
                UmlAssociation(
                    id = "Order_Items",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order", role = "order", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "OrderItem", role = "items", multiplicity = Multiplicity(0, null)),
                        ),
                )
            val model = listOf(order, item, assoc)
            val result = eval(order, "self.items", model) as List<*>
            result.size shouldBe 1
            (result.first() as UmlClass).name shouldBe "OrderItem"
        }

        // ── Multi-step association chain ────────────────────────────────────
        test("navigates a multi-step association chain") {
            val order = UmlClass(id = "Order", name = "Order")
            val customer = UmlClass(id = "Customer", name = "Customer")
            val address = UmlClass(id = "Address", name = "Address")
            val orderCustomer =
                UmlAssociation(
                    id = "Order_Customer",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order", role = "order", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "Customer", role = "customer", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val customerAddress =
                UmlAssociation(
                    id = "Customer_Address",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Customer", role = "customer", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "Address", role = "address", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val model = listOf(order, customer, address, orderCustomer, customerAddress)
            val result = eval(order, "self.customer.address", model)
            (result as UmlClass).name shouldBe "Address"
        }

        // ── Opposite navigation ──────────────────────────────────────────────
        test("navigates the opposite direction of the same association") {
            val order = UmlClass(id = "Order", name = "Order")
            val customer = UmlClass(id = "Customer", name = "Customer")
            val assoc =
                UmlAssociation(
                    id = "Order_Customer",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order", role = "orders", multiplicity = Multiplicity(0, null)),
                            UmlAssociationEnd(typeId = "Customer", role = "customer", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val model = listOf(order, customer, assoc)
            (eval(order, "self.customer", model) as UmlClass).name shouldBe "Customer"
            val opposite = eval(customer, "self.orders", model) as List<*>
            opposite.size shouldBe 1
            (opposite.first() as UmlClass).name shouldBe "Order"
        }

        // ── Unknown property still throws ───────────────────────────────────
        test("throws when neither a structural property nor an association end matches") {
            val order = UmlClass(id = "Order", name = "Order")
            shouldThrow<OclEvaluationException> { eval(order, "self.doesNotExist", listOf(order)) }
        }

        // ── Aggregation kind does not block navigation ──────────────────────
        test("navigates a composite aggregation the same as a plain association") {
            val order = UmlClass(id = "Order", name = "Order")
            val item = UmlClass(id = "OrderItem", name = "OrderItem")
            val assoc =
                UmlAssociation(
                    id = "Order_Items",
                    aggregation = AggregationKind.COMPOSITE,
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order", role = "order", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "OrderItem", role = "items", multiplicity = Multiplicity(0, null)),
                        ),
                )
            val model = listOf(order, item, assoc)
            val result = eval(order, "self.items", model) as List<*>
            result.size shouldBe 1
        }

        // ── closure() over an acyclic chain ─────────────────────────────────
        test("closure collects all transitively reachable classifiers") {
            val a = UmlClass(id = "A", name = "A")
            val b = UmlClass(id = "B", name = "B")
            val c = UmlClass(id = "C", name = "C")
            val ab =
                UmlAssociation(
                    id = "A_B",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "A", role = "a", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "B", role = "next", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val bc =
                UmlAssociation(
                    id = "B_C",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "B", role = "b", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "C", role = "next", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val model = listOf(a, b, c, ab, bc)
            val result = eval(a, "self->closure(x | x.next)", model) as List<*>
            result.map { (it as UmlClass).name }.toSet() shouldBe setOf("B", "C")
        }

        // ── closure() is cycle-safe ──────────────────────────────────────────
        test("closure terminates and de-duplicates over a cyclic association graph") {
            val a = UmlClass(id = "A", name = "A")
            val b = UmlClass(id = "B", name = "B")
            val ab =
                UmlAssociation(
                    id = "A_B",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "A", role = "a", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "B", role = "next", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val ba =
                UmlAssociation(
                    id = "B_A",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "B", role = "b", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "A", role = "next", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val model = listOf(a, b, ab, ba)
            val result = eval(a, "self->closure(x | x.next)", model) as List<*>
            result.map { (it as UmlClass).name }.toSet() shouldBe setOf("A", "B")
            result.size shouldBe 2
        }

        // ── closure() with a self-referential loop of one class ─────────────
        test("closure handles a self-referential single-node cycle without looping forever") {
            val a = UmlClass(id = "A", name = "A")
            val selfLoop =
                UmlAssociation(
                    id = "A_A",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "A", role = "a1", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "A", role = "next", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val model = listOf(a, selfLoop)
            val result = eval(a, "self->closure(x | x.next)", model) as List<*>
            result.map { (it as UmlClass).name }.toSet() shouldBe setOf("A")
            result.size shouldBe 1
        }

        // ── Declared attribute lookup (metamodel-driven, V3.2.21) ────────────
        test("navigates a declared attribute by name to its UmlProperty") {
            val ageProp = UmlProperty(id = "Person_age", name = "age", type = UmlTypeRef(name = "Integer"))
            val person = UmlClass(id = "Person", name = "Person", attributes = listOf(ageProp))
            val result = eval(person, "self.age", listOf(person))
            (result as UmlProperty).name shouldBe "age"
        }

        // ── Declared attribute takes priority over association-end navigation ──
        test("prefers a declared attribute over a same-named association end") {
            val customerProp =
                UmlProperty(id = "Order_customer", name = "customer", type = UmlTypeRef(name = "String"))
            val order = UmlClass(id = "Order", name = "Order", attributes = listOf(customerProp))
            val customer = UmlClass(id = "Customer", name = "Customer")
            val assoc =
                UmlAssociation(
                    id = "Order_Customer",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order", role = "order", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "Customer", role = "customer", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val model = listOf(order, customer, assoc)
            val result = eval(order, "self.customer", model)
            (result as UmlProperty).name shouldBe "customer"
        }

        // ── Non-navigable association end is not traversable ────────────────
        test("does not navigate an association end marked non-navigable") {
            val order = UmlClass(id = "Order", name = "Order")
            val customer = UmlClass(id = "Customer", name = "Customer")
            val assoc =
                UmlAssociation(
                    id = "Order_Customer",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Order", role = "order", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(
                                typeId = "Customer",
                                role = "customer",
                                multiplicity = Multiplicity(1, 1),
                                navigable = false,
                            ),
                        ),
                )
            val model = listOf(order, customer, assoc)
            shouldThrow<OclEvaluationException> { eval(order, "self.customer", model) }
        }

        // ── Navigable end still resolves when the opposite end is not ───────
        test("navigates a navigable end even when the opposite end is non-navigable") {
            val order = UmlClass(id = "Order", name = "Order")
            val customer = UmlClass(id = "Customer", name = "Customer")
            val assoc =
                UmlAssociation(
                    id = "Order_Customer",
                    ends =
                        listOf(
                            UmlAssociationEnd(
                                typeId = "Order",
                                role = "orders",
                                multiplicity = Multiplicity(0, null),
                                navigable = false,
                            ),
                            UmlAssociationEnd(typeId = "Customer", role = "customer", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val model = listOf(order, customer, assoc)
            (eval(order, "self.customer", model) as UmlClass).name shouldBe "Customer"
            shouldThrow<OclEvaluationException> { eval(customer, "self.orders", model) }
        }
    })
