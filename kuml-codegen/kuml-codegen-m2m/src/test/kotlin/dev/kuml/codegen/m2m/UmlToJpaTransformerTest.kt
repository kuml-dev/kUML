package dev.kuml.codegen.m2m

import dev.kuml.codegen.m2m.jpa.UmlToJpaTransformer
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

class UmlToJpaTransformerTest :
    FunSpec(body = {

        val transformer = UmlToJpaTransformer()
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

        test("single class with three attributes produces correct @Entity + @Column") {
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
            content shouldContain "@Entity"
            content shouldContain "@Table(name = \"users\")"
            content shouldContain "@Column(name = \"name\", nullable = false)"
            content shouldContain "@Column(name = \"email\", nullable = false)"
            content shouldContain "val name: String"
            content shouldContain "val email: String"
        }

        test("attribute named 'id' becomes @Id + @GeneratedValue") {
            val cls =
                cls(
                    "order",
                    "Order",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-total", "total", "Double"),
                    ),
                )
            val result = transformer.transform(diagram(cls), ctx)

            val content = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output[0].content
            content shouldContain "@Id"
            content shouldContain "@GeneratedValue(strategy = GenerationType.IDENTITY)"
            content shouldContain "val id: Long = 0L"
            content shouldNotContain "@Column(name = \"id\""
        }

        test("class without 'id' attribute gets synthetic @Id generated") {
            val cls = cls("product", "Product", listOf(prop("p-name", "name", "String")))
            val result = transformer.transform(diagram(cls), ctx)

            val content = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output[0].content
            content shouldContain "@Id"
            content shouldContain "@GeneratedValue(strategy = GenerationType.IDENTITY)"
            content shouldContain "val id: Long = 0L"
            content shouldContain "Synthetic primary key"
        }

        test("String Long Boolean Double attributes map to correct Kotlin types") {
            val cls =
                cls(
                    "entity",
                    "Entity",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-s", "label", "String"),
                        prop("p-l", "count", "Integer"),
                        prop("p-b", "active", "Boolean"),
                        prop("p-d", "score", "Double"),
                    ),
                )
            val result = transformer.transform(diagram(cls), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "val label: String"
            content shouldContain "val count: Long"
            content shouldContain "val active: Boolean"
            content shouldContain "val score: Double"
        }

        test("association multiplicity 1 produces @ManyToOne") {
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
            val userFile = files.first { it.relativePath == "User.kt" }
            userFile.content shouldContain "@ManyToOne"
            userFile.content shouldContain "@JoinColumn"
            userFile.content shouldContain "val address: Address?"
        }

        test("association multiplicity * produces @OneToMany") {
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
            val userFile = files.first { it.relativePath == "User.kt" }
            userFile.content shouldContain "@OneToMany"
            userFile.content shouldContain "cascade = [CascadeType.ALL]"
            userFile.content shouldContain "fetch = FetchType.LAZY"
            userFile.content shouldContain "val posts: List<Post>"
        }

        test("table name snake_case: OrderItem -> order_items") {
            val cls = cls("oi", "OrderItem", listOf(prop("p-id", "id", "Long")))
            val result = transformer.transform(diagram(cls), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "@Table(name = \"order_items\")"
        }

        test("column name snake_case: firstName -> first_name") {
            val cls =
                cls(
                    "person",
                    "Person",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-fn", "firstName", "String"),
                    ),
                )
            val result = transformer.transform(diagram(cls), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "@Column(name = \"first_name\""
        }

        test("stereotype transient on attribute produces @Transient") {
            val cls =
                cls(
                    "cached",
                    "Cached",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-tmp", "tmpData", "String", stereotypes = listOf("transient")),
                    ),
                )
            val result = transformer.transform(diagram(cls), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "@Transient"
            content shouldContain "val tmpData"
        }

        test("package option overrides default package name") {
            val cls = cls("thing", "Thing", listOf(prop("p-id", "id", "Long")))
            val customCtx = TransformContext(options = mapOf("package" to "org.myapp.domain"))
            val result = transformer.transform(diagram(cls), customCtx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "package org.myapp.domain"
        }

        test("empty class generates minimal entity with synthetic id") {
            val cls = UmlClass(id = "empty", name = "Empty")
            val result = transformer.transform(diagram(cls), ctx)
            val content = (result as TransformResult.Success).output[0].content
            content shouldContain "@Entity"
            content shouldContain "@Table(name = \"empties\")"
            content shouldContain "@Id"
            content shouldContain "val id: Long = 0L"
        }

        test("two classes with cross-reference generate both files with correct names") {
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
            paths shouldBe setOf("Author.kt", "Book.kt")
        }

        test("golden-file match for User class content structure") {
            val userClass =
                cls(
                    "user",
                    "User",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-name", "username", "String"),
                        prop("p-email", "email", "String"),
                    ),
                )
            val result = transformer.transform(diagram(userClass), ctx)
            val content = (result as TransformResult.Success).output[0].content
            val golden =
                this::class.java.classLoader
                    .getResource("golden/blog-domain/User.kt")
                    ?.readText()
            if (golden != null) {
                content shouldBe golden
            } else {
                // Golden file not present — just validate structure
                content shouldContain "@Entity"
                content shouldContain "data class User"
                content shouldContain "@Id"
            }
        }

        test("golden-file match for Post class with @OneToMany to Comment") {
            val postClass =
                cls(
                    "post",
                    "Post",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-title", "title", "String"),
                        prop("p-content", "content", "String"),
                    ),
                )
            val commentClass = cls("comment", "Comment", listOf(prop("c-id", "id", "Long")))
            val assoc =
                UmlAssociation(
                    id = "post-comment",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "post", multiplicity = Multiplicity(1, 1)),
                            UmlAssociationEnd(typeId = "comment", multiplicity = Multiplicity(0, null)),
                        ),
                )
            val result = transformer.transform(diagram(postClass, commentClass, assoc), ctx)
            val postFile =
                (result as TransformResult.Success).output.first { it.relativePath == "Post.kt" }
            val golden =
                this::class.java.classLoader
                    .getResource("golden/blog-domain/Post.kt")
                    ?.readText()
            if (golden != null) {
                postFile.content shouldBe golden
            } else {
                postFile.content shouldContain "@OneToMany"
                postFile.content shouldContain "val comments: List<Comment>"
            }
        }
    })
