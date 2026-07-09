package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.TransformChain
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformerRegistry
import dev.kuml.codegen.sql.SqlDdlGenerator
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import io.kotest.matchers.collections.shouldContain as shouldContainItem

@Suppress("DEPRECATION") // UmlToExposedPsmTransformer deprecated V3.4.8 — superseded by uml-to-exposed-via-erm; tests kept unmodified.
class UmlToExposedPsmTransformerTest :
    FunSpec(body = {

        val transformer = UmlToExposedPsmTransformer()
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

        fun TagValue.stringOrNull(): String? = (this as? TagValue.StringVal)?.v

        // ── Tests ─────────────────────────────────────────────────────────────────

        test("transformer id is uml-to-exposed-psm") {
            transformer.id shouldBe "uml-to-exposed-psm"
        }

        // (a)
        test("class gets both Table and Entity stereotypes with matching tableName") {
            val userClass =
                cls(
                    "user",
                    "User",
                    listOf(prop("p-id", "id", "Long"), prop("p-name", "name", "String")),
                )
            val result = transformer.transform(diagram(userClass), ctx)
            val output = result.shouldBeInstanceOf<TransformResult.Success<KumlDiagram>>().output
            val outCls = output.elements.filterIsInstance<UmlClass>().first()

            val names = outCls.appliedStereotypes.map { it.stereotypeName }
            names shouldContainItem "Table"
            names shouldContainItem "Entity"

            val tableTag = outCls.appliedStereotypes.first { it.stereotypeName == "Table" }.tags["tableName"]
            val entityTag = outCls.appliedStereotypes.first { it.stereotypeName == "Entity" }.tags["tableName"]
            tableTag?.stringOrNull() shouldBe "users"
            entityTag?.stringOrNull() shouldBe "users"
        }

        // (b)
        test("plain attribute gets Column stereotype with correct columnType and name") {
            val userClass =
                cls(
                    "user",
                    "User",
                    listOf(prop("p-id", "id", "Long"), prop("p-email", "emailAddress", "String")),
                )
            val result = transformer.transform(diagram(userClass), ctx)
            val output = (result as TransformResult.Success).output
            val outCls = output.elements.filterIsInstance<UmlClass>().first()
            val emailAttr = outCls.attributes.first { it.name == "emailAddress" }

            emailAttr.appliedStereotypes.size shouldBe 1
            val colStereo = emailAttr.appliedStereotypes.first()
            colStereo.stereotypeName shouldBe "Column"
            colStereo.tags["columnType"]?.stringOrNull() shouldBe "varchar"
            colStereo.tags["name"]?.stringOrNull() shouldBe "email_address"
        }

        // (c)
        test("id attribute gets Id stereotype, not Column") {
            val userClass = cls("user", "User", listOf(prop("p-id", "id", "Long")))
            val result = transformer.transform(diagram(userClass), ctx)
            val output = (result as TransformResult.Success).output
            val outCls = output.elements.filterIsInstance<UmlClass>().first()
            val idAttr = outCls.attributes.first { it.name == "id" }

            idAttr.appliedStereotypes.any { it.stereotypeName == "Id" } shouldBe true
            idAttr.appliedStereotypes.any { it.stereotypeName == "Column" } shouldBe false
        }

        test("case-insensitive ID attribute also gets Id stereotype, not Column") {
            val userClass = cls("user2", "User2", listOf(prop("p-id", "ID", "Long")))
            val result = transformer.transform(diagram(userClass), ctx)
            val output = (result as TransformResult.Success).output
            val outCls = output.elements.filterIsInstance<UmlClass>().first()
            val idAttr = outCls.attributes.first { it.name == "ID" }

            idAttr.appliedStereotypes.any { it.stereotypeName == "Id" } shouldBe true
            idAttr.appliedStereotypes.any { it.stereotypeName == "Column" } shouldBe false
        }

        // (d)
        test("transient attribute is left unchanged") {
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
            val output = (result as TransformResult.Success).output
            val outCls = output.elements.filterIsInstance<UmlClass>().first()
            val tmpAttr = outCls.attributes.first { it.name == "tmpData" }

            tmpAttr.appliedStereotypes shouldBe emptyList()
        }

        // (e)
        test("association end gets FK stereotype") {
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
            val output = (result as TransformResult.Success).output
            val outAssoc = output.elements.filterIsInstance<UmlAssociation>().first()

            outAssoc.appliedStereotypes.size shouldBe 1
            val fk = outAssoc.appliedStereotypes.first()
            fk.stereotypeName shouldBe "FK"
            fk.tags["targetTable"]?.stringOrNull() shouldBe "addresses"
        }

        // (f)
        test("produced PSM diagram round-trips through SqlDdlGenerator into correct DDL") {
            val userClass =
                cls(
                    "user",
                    "User",
                    listOf(prop("p-id", "id", "Long"), prop("p-name", "name", "String")),
                )
            val result = transformer.transform(diagram(userClass), ctx)
            val psm = (result as TransformResult.Success).output

            val out = Files.createTempDirectory("kuml-psm-sql-test").toFile()
            try {
                val files = SqlDdlGenerator().generate(psm, out, emptyMap())
                val content = files.single().readText()
                content shouldContain "CREATE TABLE users ("
                content shouldContain "name VARCHAR(255)"
            } finally {
                out.deleteRecursively()
            }
        }

        // (g)
        test("TransformChain PIM to PSM to Exposed Table code — chain composability") {
            val userClass =
                cls(
                    "user",
                    "User",
                    listOf(prop("p-id", "id", "Long"), prop("p-name", "name", "String")),
                )
            val chain =
                TransformChain<KumlDiagram, KumlDiagram, List<GeneratedFile>>(
                    UmlToExposedPsmTransformer(),
                    UmlToExposedTransformer(),
                )
            chain.id shouldBe "uml-to-exposed-psm+uml-to-exposed"

            val result = chain.transform(diagram(userClass), ctx)
            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            val content = files.first { it.relativePath == "Users.kt" }.content

            content shouldContain "PrimaryKey(id)"
            content shouldNotContain "Synthetic primary key"
        }

        // (h)
        test("two associated classes produce two independently-annotated PSM classes") {
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
            val output = (result as TransformResult.Success).output
            val outClasses = output.elements.filterIsInstance<UmlClass>()

            val outUser = outClasses.first { it.name == "User" }
            val outAddress = outClasses.first { it.name == "Address" }

            outUser.appliedStereotypes
                .first { it.stereotypeName == "Table" }
                .tags["tableName"]
                ?.stringOrNull() shouldBe "users"
            outAddress.appliedStereotypes
                .first { it.stereotypeName == "Table" }
                .tags["tableName"]
                ?.stringOrNull() shouldBe "addresses"

            outAddress.attributes.forEach { attr ->
                attr.appliedStereotypes.any { it.stereotypeName == "FK" } shouldBe false
            }
        }

        // ── Identifier-injection guard ───────────────────────────────────────────────

        test("class with a SQL-metacharacter-laden name throws UnsafeUmlNameException") {
            val maliciousClass =
                cls(
                    "evil",
                    "User\"; DROP TABLE users; --",
                    listOf(prop("p-id", "id", "Long")),
                )
            shouldThrow<UnsafeUmlNameException> {
                transformer.transform(diagram(maliciousClass), ctx)
            }
        }

        test("attribute with a SQL-metacharacter-laden name throws UnsafeUmlNameException") {
            val maliciousClass =
                cls(
                    "evil2",
                    "User",
                    listOf(
                        prop("p-id", "id", "Long"),
                        prop("p-evil", "name\"; DROP TABLE users; --", "String"),
                    ),
                )
            shouldThrow<UnsafeUmlNameException> {
                transformer.transform(diagram(maliciousClass), ctx)
            }
        }

        test("malicious class name never reaches SqlDdlGenerator — rejected at the PSM transform step") {
            val maliciousClass =
                cls(
                    "evil3",
                    "User\"; DROP TABLE users; --",
                    listOf(prop("p-id", "id", "Long")),
                )
            // Defense in depth, layer 1: the PSM transformer itself rejects the name before any
            // «Entity»/«Column» tag is ever produced, so the malicious diagram is never even
            // handed to SqlDdlGenerator. (kuml-gen-sql's own independent guard — SqlNamesTest —
            // covers layer 2, in case a malicious tag reaches it via some other producer.)
            shouldThrow<UnsafeUmlNameException> {
                transformer.transform(diagram(maliciousClass), ctx)
            }
        }

        // ── Service-loader discovery ────────────────────────────────────────────────

        test("uml-to-exposed-psm is discovered via TransformerRegistry.loadFromClasspath") {
            TransformerRegistry.loadFromClasspath()
            TransformerRegistry.get<KumlDiagram, KumlDiagram>("uml-to-exposed-psm") shouldNotBe null
        }
    })
