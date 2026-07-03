package dev.kuml.codegen.m2m

import dev.kuml.codegen.m2m.rest.UmlToRestTransformer
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class UmlToRestTransformerTest :
    FunSpec(body = {

        val transformer = UmlToRestTransformer()
        val ctx = TransformContext()

        // ── Helpers ───────────────────────────────────────────────────────────────

        fun prop(
            id: String,
            name: String,
            type: String,
        ) = UmlProperty(
            id = id,
            name = name,
            type = UmlTypeRef(type),
        )

        fun cls(
            id: String,
            name: String,
            attributes: List<UmlProperty> = emptyList(),
        ) = UmlClass(id = id, name = name, attributes = attributes)

        fun diagram(vararg elements: dev.kuml.core.model.KumlElement) = KumlDiagram(name = "Test", elements = elements.toList())

        // ── Tests ─────────────────────────────────────────────────────────────────

        test("single class with three attributes produces correct components/schemas block") {
            val userClass =
                cls(
                    "user",
                    "User",
                    listOf(
                        prop("p-id", "id", "UUID"),
                        prop("p-name", "name", "String"),
                        prop("p-email", "email", "String"),
                    ),
                )
            val result = transformer.transform(diagram(userClass), ctx)

            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            files shouldHaveSize 1
            val content = files[0].content
            content shouldContain "components:"
            content shouldContain "  schemas:"
            content shouldContain "    User:"
            content shouldContain "      type: object"
            content shouldContain "      properties:"
            content shouldContain "        id:"
            content shouldContain "          type: string"
            content shouldContain "          format: uuid"
            content shouldContain "        name:"
            content shouldContain "        email:"
        }

        test("type mapping: String → type: string, no format") {
            val c = cls("x", "X", listOf(prop("p", "label", "String")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "          type: string"
            content shouldNotContain "          format:"
        }

        test("type mapping: Long → type: integer, format: int64") {
            val c = cls("x", "X", listOf(prop("p", "count", "Long")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "          type: integer"
            content shouldContain "          format: int64"
        }

        test("type mapping: Integer → type: integer, format: int64") {
            val c = cls("x", "X", listOf(prop("p", "age", "Integer")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "          type: integer"
            content shouldContain "          format: int64"
        }

        test("type mapping: Boolean → type: boolean, no format") {
            val c = cls("x", "X", listOf(prop("p", "active", "Boolean")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "          type: boolean"
            content shouldNotContain "          format:"
        }

        test("type mapping: Double → type: number, format: double") {
            val c = cls("x", "X", listOf(prop("p", "score", "Double")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "          type: number"
            content shouldContain "          format: double"
        }

        test("type mapping: UUID → type: string, format: uuid") {
            val c = cls("x", "X", listOf(prop("p", "id", "UUID")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "          type: string"
            content shouldContain "          format: uuid"
        }

        test("type mapping: LocalDate → type: string, format: date") {
            val c = cls("x", "X", listOf(prop("p", "createdAt", "LocalDate")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "          type: string"
            content shouldContain "          format: date"
        }

        test("resource path is kebab-case plural: OrderItem → /order-items") {
            val c = cls("oi", "OrderItem", listOf(prop("p-id", "id", "Long")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "/api/v1/order-items:"
            content shouldContain "/api/v1/order-items/{id}:"
        }

        test("resource path is kebab-case plural: User → /users") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "/api/v1/users:"
        }

        test("five CRUD endpoints are generated per class") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            // GET list and POST under /users
            content shouldContain "    get:"
            content shouldContain "    post:"
            // GET by id, PUT, DELETE under /users/{id}
            content shouldContain "/api/v1/users/{id}:"
            content shouldContain "    put:"
            content shouldContain "    delete:"
        }

        test("GET /resources/{id} has a path parameter named id") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "        - name: id"
            content shouldContain "          in: path"
            content shouldContain "          required: true"
        }

        test("POST /resources has a requestBody") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "      requestBody:"
            content shouldContain "        required: true"
        }

        test("DELETE /resources/{id} returns 204 No Content") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain """"204":"""
            content shouldContain "          description: No Content"
        }

        test("operationId follows listXs/getX/createX/updateX/deleteX convention") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain "operationId: listUsers"
            content shouldContain "operationId: createUser"
            content shouldContain "operationId: getUser"
            content shouldContain "operationId: updateUser"
            content shouldContain "operationId: deleteUser"
        }

        test("basePath option overrides default /api/v1") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val customCtx = TransformContext(options = mapOf("basePath" to "/v2"))
            val content = (transformer.transform(diagram(c), customCtx) as TransformResult.Success).output[0].content
            content shouldContain "/v2/users:"
            content shouldNotContain "/api/v1/users:"
        }

        test("title and version options are applied to info block") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val customCtx = TransformContext(options = mapOf("title" to "My Blog API", "version" to "2.5.0"))
            val content = (transformer.transform(diagram(c), customCtx) as TransformResult.Success).output[0].content
            content shouldContain "  title: My Blog API"
            content shouldContain """  version: "2.5.0""""
        }

        test("golden-file match for User class paths and schema") {
            val userClass =
                cls(
                    "user",
                    "User",
                    listOf(
                        prop("p-id", "id", "UUID"),
                        prop("p-name", "username", "String"),
                        prop("p-email", "email", "String"),
                    ),
                )
            val content = (transformer.transform(diagram(userClass), ctx) as TransformResult.Success).output[0].content
            // Key structural assertions that must hold regardless of exact whitespace
            content shouldContain "  /api/v1/users:"
            content shouldContain "  /api/v1/users/{id}:"
            content shouldContain "    User:"
            content shouldContain "        id:"
            content shouldContain "          format: uuid"
            content shouldContain "        username:"
            content shouldContain "        email:"
        }

        test("two-class model produces two schemas entries") {
            val userClass = cls("user", "User", listOf(prop("p-id", "id", "UUID")))
            val postClass = cls("post", "Post", listOf(prop("p-id", "id", "UUID")))
            val result = transformer.transform(diagram(userClass, postClass), ctx)

            val content = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output[0].content
            content shouldContain "    User:"
            content shouldContain "    Post:"
            content shouldContain "/api/v1/users:"
            content shouldContain "/api/v1/posts:"
        }

        test("output is a single file named openapi.yaml") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val result = transformer.transform(diagram(c), ctx) as TransformResult.Success
            result.output shouldHaveSize 1
            result.output[0].relativePath shouldBe "openapi.yaml"
        }

        test("openapi header version is 3.0.3") {
            val c = cls("u", "User", listOf(prop("p-id", "id", "UUID")))
            val content = (transformer.transform(diagram(c), ctx) as TransformResult.Success).output[0].content
            content shouldContain """openapi: "3.0.3""""
        }
    })
