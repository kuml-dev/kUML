package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

@Suppress("DEPRECATION") // UmlToExposedTransformer deprecated V3.4.8 — superseded by uml-to-exposed-via-erm; tests kept unmodified.
class UmlToExposedTransformerTest :
    FunSpec(body = {

        val transformer = UmlToExposedTransformer()
        val ctx = TransformContext()

        // ── Helpers ───────────────────────────────────────────────────────────────

        fun prop(
            id: String,
            name: String,
            type: String,
            stereotypes: List<String> = emptyList(),
        ) = UmlProperty(
            id = id,
            name = name,
            type = UmlTypeRef(type),
            stereotypes = stereotypes,
        )

        fun cls(
            id: String,
            name: String,
            attributes: List<UmlProperty> = emptyList(),
        ) = UmlClass(id = id, name = name, attributes = attributes)

        fun diagram(vararg elements: dev.kuml.core.model.KumlElement) = KumlDiagram(name = "Test", elements = elements.toList())

        // ── Tests ─────────────────────────────────────────────────────────────────

        test("basic entity produces correct Table object") {
            val userClass =
                cls(
                    "user",
                    "User",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-name", "name", "String"),
                        prop("p-email", "email", "String"),
                    ),
                )
            val result = transformer.transform(diagram(userClass), ctx)

            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            files shouldHaveSize 1
            val content = files[0].content
            content shouldContain "public object Users : Table(\"users\")"
            content shouldContain "val name: Column<String> = varchar(\"name\", 255)"
            content shouldContain "val email: Column<String> = varchar(\"email\", 255)"
            content shouldContain "override val primaryKey: PrimaryKey = PrimaryKey(id)"
        }

        test("class without 'id' attribute gets synthetic primary key") {
            val productClass = cls("product", "Product", listOf(prop("p-name", "name", "String")))
            val result = transformer.transform(diagram(productClass), ctx)

            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "Synthetic primary key"
            content shouldContain "val id: Column<Long> = long(\"id\").autoIncrement()"
            content shouldContain "PrimaryKey(id)"
        }

        test("attribute with «id» stereotype becomes primary key even when not named 'id'") {
            val orderClass =
                cls(
                    "order",
                    "Order",
                    listOf(
                        prop("p-oid", "orderId", "Long", stereotypes = listOf("id")),
                        prop("p-total", "total", "Double"),
                    ),
                )
            val result = transformer.transform(diagram(orderClass), ctx)

            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "val orderId: Column<Long> = long(\"order_id\").autoIncrement()"
            content shouldContain "PrimaryKey(orderId)"
            content shouldNotContain "Synthetic primary key"
        }

        test("String Integer Boolean Double attributes map to correct Exposed columns") {
            val entityClass =
                cls(
                    "entity",
                    "Entity",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-s", "label", "String"),
                        prop("p-i", "count", "Integer"),
                        prop("p-b", "active", "Boolean"),
                        prop("p-d", "score", "Double"),
                    ),
                )
            val result = transformer.transform(diagram(entityClass), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "val label: Column<String> = varchar(\"label\", 255)"
            content shouldContain "val count: Column<Int> = integer(\"count\")"
            content shouldContain "val active: Column<Boolean> = bool(\"active\")"
            content shouldContain "val score: Column<Double> = double(\"score\")"
        }

        test("association multiplicity 1 produces FK column via reference()") {
            val userClass = cls("user", "User", listOf(prop("p-id", "id", "Long")))
            val addressClass = cls("address", "Address", listOf(prop("p-id", "id", "Long")))
            val assoc =
                UmlAssociation(
                    id = "assoc-1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "user", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "address", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val result = transformer.transform(diagram(userClass, addressClass, assoc), ctx)

            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            val userFile = files.first { it.relativePath == "Users.kt" }
            userFile.content shouldContain "val addressId: Column<Long> = reference(\"address_id\", Addresses)"
        }

        test("association multiplicity * produces no Table column, only a comment note") {
            val userClass = cls("user", "User", listOf(prop("p-id", "id", "Long")))
            val postClass = cls("post", "Post", listOf(prop("p-id", "id", "Long")))
            val assoc =
                UmlAssociation(
                    id = "assoc-2",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "user", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "post", multiplicity = Multiplicity(0, null)),
                        ),
                )
            val result = transformer.transform(diagram(userClass, postClass, assoc), ctx)

            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            val userFile = files.first { it.relativePath == "Users.kt" }
            userFile.content shouldNotContain "val posts"
            userFile.content shouldContain "*-to-many association(s) are not represented on the Table object"
        }

        test("table name snake_case + pluralization: OrderItem -> order_items") {
            val orderItemClass = cls("oi", "OrderItem", listOf(prop("p-id", "id", "Long")))
            val result = transformer.transform(diagram(orderItemClass), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "public object OrderItems : Table(\"order_items\")"
        }

        test("column name snake_case: firstName -> first_name") {
            val personClass =
                cls(
                    "person",
                    "Person",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-fn", "firstName", "String"),
                    ),
                )
            val result = transformer.transform(diagram(personClass), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "varchar(\"first_name\", 255)"
        }

        test("stereotype transient on attribute is skipped as non-column") {
            val cachedClass =
                cls(
                    "cached",
                    "Cached",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-tmp", "tmpData", "String", stereotypes = listOf("transient")),
                    ),
                )
            val result = transformer.transform(diagram(cachedClass), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldNotContain "val tmpData: Column"
            content shouldContain "'tmpData' is «transient» — skipped"
        }

        test("package option overrides default package name") {
            val thingClass = cls("thing", "Thing", listOf(prop("p-id", "id", "Long")))
            val customCtx = TransformContext(options = mapOf("package" to "org.myapp.tables"))
            val result = transformer.transform(diagram(thingClass), customCtx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "package org.myapp.tables"
        }

        test("two classes with cross-reference generate both files with pluralized names") {
            val authorClass = cls("author", "Author", listOf(prop("p-id", "id", "Long")))
            val bookClass = cls("book", "Book", listOf(prop("p-id", "id", "Long")))
            val assoc =
                UmlAssociation(
                    id = "assoc-ab",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "author", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "book", multiplicity = Multiplicity(0, null)),
                        ),
                )
            val result = transformer.transform(diagram(authorClass, bookClass, assoc), ctx)

            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            files shouldHaveSize 2
            val paths = files.map { it.relativePath }.toSet()
            paths shouldBe setOf("Authors.kt", "Books.kt")
        }

        test("empty class generates minimal table with synthetic id") {
            val emptyClass = UmlClass(id = "empty", name = "Empty")
            val result = transformer.transform(diagram(emptyClass), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "public object Empties : Table(\"empties\")"
            content shouldContain "PrimaryKey(id)"
        }

        test("self-referential association is skipped with a comment, not a broken reference()") {
            val employeeClass = cls("employee", "Employee", listOf(prop("p-id", "id", "Long")))
            val assoc =
                UmlAssociation(
                    id = "assoc-self",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "employee", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "employee", multiplicity = Multiplicity(0, 1)),
                        ),
                )
            val result = transformer.transform(diagram(employeeClass, assoc), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldNotContain "reference(\"employee_id\", Employees)"
            content shouldContain "self-referential association(s) skipped"
        }

        // ── Adversarial / security tests ────────────────────────────────────────────

        test("attribute name with embedded double quote and Kotlin code fails the transform, not silently emitted") {
            val evilClass =
                cls(
                    "evil",
                    "Evil",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop(
                            "p-x",
                            "x\") { init { Runtime.getRuntime().exec(\"touch pwned",
                            "String",
                        ),
                    ),
                )
            val result = transformer.transform(diagram(evilClass), ctx)
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("attribute name with dollar template-interpolation sequence fails the transform") {
            val evilClass =
                cls(
                    "evil2",
                    "Evil2",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-y", "\${System.getenv(\"SECRET\")}", "String"),
                    ),
                )
            val result = transformer.transform(diagram(evilClass), ctx)
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("attribute name with backslash and newline fails the transform") {
            val evilClass =
                cls(
                    "evil3",
                    "Evil3",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-z", "a\\nb\nval hacked = true", "String"),
                    ),
                )
            val result = transformer.transform(diagram(evilClass), ctx)
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("class name with path-traversal sequence fails the transform, never escapes output dir") {
            val evilClass = cls("evil4", "../../../../tmp/evil", listOf(prop("p-id", "id", "Long")))
            val result = transformer.transform(diagram(evilClass), ctx)
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("class name that is a Kotlin hard keyword fails the transform") {
            val evilClass = cls("evil5", "object", listOf(prop("p-id", "id", "Long")))
            val result = transformer.transform(diagram(evilClass), ctx)
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("attribute name that is a Kotlin hard keyword fails the transform") {
            val evilClass =
                cls(
                    "evil6",
                    "Evil6",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-kw", "class", "String"),
                    ),
                )
            val result = transformer.transform(diagram(evilClass), ctx)
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }

        test("association target class name with injection payload fails the transform") {
            val userClass = cls("user", "User", listOf(prop("p-id", "id", "Long")))
            val evilTarget =
                cls("evilTarget", "X\") { init { println(\"pwned", listOf(prop("p-id", "id", "Long")))
            val assoc =
                UmlAssociation(
                    id = "assoc-evil",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "user", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "evilTarget", multiplicity = Multiplicity(1, 1)),
                        ),
                )
            val result = transformer.transform(diagram(userClass, evilTarget, assoc), ctx)
            result.shouldBeInstanceOf<TransformResult.Failure>()
        }

        test(
            "Exposed Table member-name collision (e.g. 'columns') is accepted as a valid " +
                "identifier but documented as a known limitation",
        ) {
            // Not a security issue per se (still a safe Kotlin identifier), but worth a
            // regression anchor: this class of name is explicitly called out in the KDoc
            // "Known limitations" section as producing non-compiling output downstream.
            val collidingClass =
                cls(
                    "colliding",
                    "Colliding",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-columns", "columns", "String"),
                    ),
                )
            val result = transformer.transform(diagram(collidingClass), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "val columns: Column<String>"
        }
    })
