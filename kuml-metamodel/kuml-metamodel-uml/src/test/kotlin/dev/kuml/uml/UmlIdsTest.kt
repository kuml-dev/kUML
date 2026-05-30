package dev.kuml.uml

import dev.kuml.uml.ids.UmlIds
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class UmlIdsTest : FunSpec(body = {

    // ── child ─────────────────────────────────────────────────────────────────

    test(name = "child with null parent returns name as-is") {
        UmlIds.child(parentId = null, name = "Order") shouldBe "Order"
    }

    test(name = "child with empty parent returns name as-is") {
        UmlIds.child(parentId = "", name = "Order") shouldBe "Order"
    }

    test(name = "child with parent prepends parent with separator") {
        UmlIds.child(parentId = "domain", name = "Order") shouldBe "domain::Order"
    }

    test(name = "child chains correctly for deep nesting") {
        val pkg = UmlIds.child(parentId = null, name = "com")
        val subpkg = UmlIds.child(parentId = pkg, name = "example")
        val cls = UmlIds.child(parentId = subpkg, name = "Order")
        cls shouldBe "com::example::Order"
    }

    // ── operation ────────────────────────────────────────────────────────────

    test(name = "operation with no parameters uses empty parentheses") {
        UmlIds.operation(
            ownerId = "domain::Order",
            name = "confirm",
            paramTypes = emptyList(),
        ) shouldBe "domain::Order::confirm()"
    }

    test(name = "operation with one parameter includes type in parentheses") {
        UmlIds.operation(
            ownerId = "domain::Order",
            name = "find",
            paramTypes = listOf("Long"),
        ) shouldBe "domain::Order::find(Long)"
    }

    test(name = "operation disambiguates overloads via parameter types") {
        val noParam = UmlIds.operation(ownerId = "Svc", name = "process", paramTypes = emptyList())
        val withParam = UmlIds.operation(ownerId = "Svc", name = "process", paramTypes = listOf("String"))
        noParam shouldBe "Svc::process()"
        withParam shouldBe "Svc::process(String)"
        (noParam == withParam) shouldBe false
    }

    test(name = "operation with multiple parameters joins types with comma") {
        UmlIds.operation(
            ownerId = "Repo",
            name = "findBy",
            paramTypes = listOf("Long", "String", "Boolean"),
        ) shouldBe "Repo::findBy(Long,String,Boolean)"
    }

    // ── relationship IDs ──────────────────────────────────────────────────────

    test(name = "association without name uses arrow notation") {
        UmlIds.association(
            sourceId = "Order",
            targetId = "Item",
            name = null,
        ) shouldBe "assoc::Order-->Item"
    }

    test(name = "association with name appends name after separator") {
        UmlIds.association(
            sourceId = "domain::Order",
            targetId = "domain::Item",
            name = "contains",
        ) shouldBe "assoc::domain::Order-->domain::Item::contains"
    }

    test(name = "generalization uses -|> notation") {
        UmlIds.generalization(specificId = "Dog", generalId = "Animal") shouldBe "gen::Dog-|>Animal"
    }

    test(name = "realization uses ..|> notation") {
        UmlIds.realization(implementingId = "OrderSvc", interfaceId = "IOrderSvc") shouldBe
            "real::OrderSvc..|>IOrderSvc"
    }

    test(name = "dependency uses ..> notation") {
        UmlIds.dependency(clientId = "Order", supplierId = "OrderStatus") shouldBe
            "dep::Order..>OrderStatus"
    }

    test(name = "include uses ..> notation with include prefix") {
        UmlIds.include(baseId = "Checkout", additionId = "ValidateCart") shouldBe
            "include::Checkout..>ValidateCart"
    }

    test(name = "extend uses ..> notation with extend prefix") {
        UmlIds.extend(baseId = "Checkout", extensionId = "ApplyDiscount") shouldBe
            "extend::Checkout..>ApplyDiscount"
    }

    test(name = "connector uses -- notation") {
        UmlIds.connector(end1Id = "portA", end2Id = "portB") shouldBe "conn::portA--portB"
    }

    // ── interaction IDs ───────────────────────────────────────────────────────

    test(name = "lifeline ID contains interaction prefix and ll segment") {
        UmlIds.lifeline(interactionId = "PlaceOrder", name = "Customer") shouldBe
            "PlaceOrder::ll::Customer"
    }

    test(name = "message ID uses sequence index not label") {
        UmlIds.message(interactionId = "PlaceOrder", sequence = 3) shouldBe
            "PlaceOrder::msg::3"
    }

    test(name = "message IDs are unique per sequence number") {
        val msg1 = UmlIds.message("I", 1)
        val msg2 = UmlIds.message("I", 2)
        (msg1 == msg2) shouldBe false
    }

    test(name = "fragment ID uses index") {
        UmlIds.fragment(interactionId = "PlaceOrder", index = 1) shouldBe
            "PlaceOrder::frag::1"
    }

    // ── state machine IDs ─────────────────────────────────────────────────────

    test(name = "vertex ID is stateMachineId :: name") {
        UmlIds.vertex(stateMachineId = "OrderSM", name = "CONFIRMED") shouldBe
            "OrderSM::CONFIRMED"
    }

    test(name = "transition ID without disambiguation") {
        UmlIds.transition(
            stateMachineId = "OrderSM",
            sourceName = "DRAFT",
            targetName = "CONFIRMED",
        ) shouldBe "OrderSM::t::DRAFT->CONFIRMED"
    }

    test(name = "transition ID with disambiguation appends hash index") {
        val t =
            UmlIds.transition(
                stateMachineId = "OrderSM",
                sourceName = "DRAFT",
                targetName = "CANCELLED",
                disambiguationIndex = 2,
            )
        t shouldBe "OrderSM::t::DRAFT->CANCELLED#2"
    }

    // ── disambiguate ──────────────────────────────────────────────────────────

    test(name = "disambiguate returns candidate unchanged when not taken") {
        UmlIds.disambiguate(candidate = "domain::Order", taken = emptySet()) shouldBe
            "domain::Order"
    }

    test(name = "disambiguate appends ~2 for first collision") {
        UmlIds.disambiguate(
            candidate = "domain::Order",
            taken = setOf("domain::Order"),
        ) shouldBe "domain::Order~2"
    }

    test(name = "disambiguate finds next free slot when multiple suffixes taken") {
        val result =
            UmlIds.disambiguate(
                candidate = "X",
                taken = setOf("X", "X~2", "X~3"),
            )
        result shouldBe "X~4"
    }

    test(name = "disambiguate result is not in the taken set") {
        val taken = setOf("A", "A~2")
        val result = UmlIds.disambiguate(candidate = "A", taken = taken)
        (result in taken) shouldBe false
    }

    // ── SEP constant ─────────────────────────────────────────────────────────

    test(name = "SEP constant is double colon") {
        UmlIds.SEP shouldBe "::"
    }

    test(name = "all qualified IDs contain the SEP when nested") {
        UmlIds.child(parentId = "a", name = "b") shouldContain UmlIds.SEP
    }

    test(name = "root-level child does not contain SEP") {
        val id = UmlIds.child(parentId = null, name = "Order")
        id.contains(UmlIds.SEP) shouldBe false
    }

    // ── consistency: child + operation pipeline ───────────────────────────────

    test(name = "building an attribute ID via child matches expected format") {
        val classId = UmlIds.child(parentId = "domain", name = "Order")
        val attrId = UmlIds.child(parentId = classId, name = "id")
        attrId shouldBe "domain::Order::id"
    }

    test(name = "building an operation ID via child + operation matches expected format") {
        val classId = UmlIds.child(parentId = "domain", name = "Order")
        val opId = UmlIds.operation(ownerId = classId, name = "cancel", paramTypes = emptyList())
        opId shouldBe "domain::Order::cancel()"
        opId shouldStartWith classId
    }
})
