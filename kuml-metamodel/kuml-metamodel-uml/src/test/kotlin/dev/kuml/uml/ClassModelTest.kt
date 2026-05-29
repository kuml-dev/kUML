package dev.kuml.uml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ClassModelTest : FunSpec({

    // ── UmlClass ──────────────────────────────────────────────────────────────

    test("minimal class builds with required fields only") {
        val cls = UmlClass(id = "Order", name = "Order")
        cls.id shouldBe "Order"
        cls.name shouldBe "Order"
        cls.visibility shouldBe Visibility.PUBLIC
        cls.isAbstract shouldBe false
        cls.attributes.shouldBeEmpty()
        cls.operations.shouldBeEmpty()
        cls.constraints.shouldBeEmpty()
        cls.stereotypes.shouldBeEmpty()
        cls.metadata.isEmpty() shouldBe true
    }

    test("abstract class is modelled with isAbstract = true") {
        val cls = UmlClass(id = "AbstractBase", name = "AbstractBase", isAbstract = true)
        cls.isAbstract shouldBe true
    }

    test("class with attributes stores all properties") {
        val idProp =
            UmlProperty(
                id = "Order::id",
                name = "id",
                type = UmlTypeRef(name = "UUID"),
                visibility = Visibility.PRIVATE,
            )
        val statusProp =
            UmlProperty(
                id = "Order::status",
                name = "status",
                type = UmlTypeRef(name = "OrderStatus"),
                visibility = Visibility.PRIVATE,
            )
        val cls =
            UmlClass(
                id = "Order",
                name = "Order",
                attributes = listOf(idProp, statusProp),
            )
        cls.attributes shouldHaveSize 2
        cls.attributes[0].name shouldBe "id"
        cls.attributes[0].type.name shouldBe "UUID"
        cls.attributes[1].name shouldBe "status"
    }

    test("class with operations stores all operations") {
        val confirm =
            UmlOperation(
                id = "Order::confirm()",
                name = "confirm",
                visibility = Visibility.PUBLIC,
            )
        val cancel =
            UmlOperation(
                id = "Order::cancel()",
                name = "cancel",
                returnType = UmlTypeRef(name = "Boolean"),
            )
        val cls =
            UmlClass(
                id = "Order",
                name = "Order",
                operations = listOf(confirm, cancel),
            )
        cls.operations shouldHaveSize 2
        cls.operations[0].name shouldBe "confirm"
        cls.operations[0].returnType shouldBe null
        cls.operations[1].returnType?.name shouldBe "Boolean"
    }

    test("class is a UmlElement and UmlNamedElement") {
        val cls = UmlClass(id = "X", name = "X")
        cls.shouldBeInstanceOf<UmlElement>()
        cls.shouldBeInstanceOf<UmlNamedElement>()
        cls.shouldBeInstanceOf<UmlClassifier>()
    }

    // ── UmlInterface ──────────────────────────────────────────────────────────

    test("interface builds with required fields only") {
        val iface = UmlInterface(id = "IOrderSvc", name = "IOrderSvc")
        iface.name shouldBe "IOrderSvc"
        iface.operations.shouldBeEmpty()
        iface.shouldBeInstanceOf<UmlClassifier>()
    }

    test("interface holds operations") {
        val op = UmlOperation(id = "IOrderSvc::place()", name = "place")
        val iface =
            UmlInterface(
                id = "IOrderSvc",
                name = "IOrderSvc",
                operations = listOf(op),
            )
        iface.operations shouldHaveSize 1
    }

    // ── UmlEnumeration ────────────────────────────────────────────────────────

    test("enumeration holds literals") {
        val draft = UmlEnumerationLiteral(id = "OrderStatus::DRAFT", name = "DRAFT")
        val confirmed = UmlEnumerationLiteral(id = "OrderStatus::CONFIRMED", name = "CONFIRMED")
        val enum =
            UmlEnumeration(
                id = "OrderStatus",
                name = "OrderStatus",
                literals = listOf(draft, confirmed),
            )
        enum.literals shouldHaveSize 2
        enum.literals[0].name shouldBe "DRAFT"
        enum.literals[1].name shouldBe "CONFIRMED"
        enum.shouldBeInstanceOf<UmlClassifier>()
    }

    // ── UmlPackage ────────────────────────────────────────────────────────────

    test("package holds named members") {
        val cls = UmlClass(id = "domain::Order", name = "Order")
        val enum = UmlEnumeration(id = "domain::Status", name = "Status")
        val pkg =
            UmlPackage(
                id = "domain",
                name = "domain",
                members = listOf(cls, enum),
            )
        pkg.members shouldHaveSize 2
        pkg.shouldBeInstanceOf<UmlNamedElement>()
    }

    test("packages can be nested") {
        val inner = UmlPackage(id = "outer::inner", name = "inner")
        val outer =
            UmlPackage(
                id = "outer",
                name = "outer",
                members = listOf(inner),
            )
        outer.members[0].shouldBeInstanceOf<UmlPackage>()
    }

    // ── UmlProperty ───────────────────────────────────────────────────────────

    test("property defaults to PRIVATE visibility") {
        val prop = UmlProperty(id = "X::x", name = "x", type = UmlTypeRef(name = "Int"))
        prop.visibility shouldBe Visibility.PRIVATE
    }

    test("property holds multiplicity") {
        val prop =
            UmlProperty(
                id = "Order::items",
                name = "items",
                type = UmlTypeRef(name = "OrderItem"),
                multiplicity = Multiplicity(lower = 1, upper = null),
            )
        prop.multiplicity.lower shouldBe 1
        prop.multiplicity.upper shouldBe null
    }

    test("static read-only property is modelled correctly") {
        val prop =
            UmlProperty(
                id = "Config::MAX",
                name = "MAX",
                type = UmlTypeRef(name = "Int"),
                isStatic = true,
                isReadOnly = true,
            )
        prop.isStatic shouldBe true
        prop.isReadOnly shouldBe true
    }

    // ── UmlOperation ──────────────────────────────────────────────────────────

    test("operation with parameters stores them in order") {
        val p1 =
            UmlParameter(
                id = "Order::find(Long,String)::id",
                name = "id",
                type = UmlTypeRef(name = "Long"),
            )
        val p2 =
            UmlParameter(
                id = "Order::find(Long,String)::hint",
                name = "hint",
                type = UmlTypeRef(name = "String"),
            )
        val op =
            UmlOperation(
                id = "Order::find(Long,String)",
                name = "find",
                parameters = listOf(p1, p2),
                returnType = UmlTypeRef(name = "Order"),
            )
        op.parameters shouldHaveSize 2
        op.parameters[0].name shouldBe "id"
        op.parameters[1].name shouldBe "hint"
        op.returnType?.name shouldBe "Order"
    }

    // ── UmlConstraint ─────────────────────────────────────────────────────────

    test("constraint stores OCL body as raw string") {
        val c =
            UmlConstraint(
                id = "Order::hasItems",
                name = "hasItems",
                body = "self.items->size() > 0",
            )
        c.body shouldBe "self.items->size() > 0"
        c.language shouldBe "OCL"
    }
})
