package dev.kuml.codegen.java

import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.TagValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private data class TestAppliedStereotype(
    override val profileNamespace: String,
    override val stereotypeName: String,
    override val tags: Map<String, TagValue>,
) : AppliedStereotype

class JavaStereotypeMapperTest :
    FunSpec({

        fun applied(
            name: String,
            tags: Map<String, TagValue> = emptyMap(),
        ) = TestAppliedStereotype(profileNamespace = "test.profile", stereotypeName = name, tags = tags)

        test("Entity stereotype with tableName tag produces @Entity(name=\"…\")") {
            val app = applied("Entity", mapOf("tableName" to TagValue.StringVal("users")))
            JavaStereotypeMapper.toAnnotation(app) shouldBe
                "@jakarta.persistence.Entity(name = \"users\")"
        }

        test("Entity stereotype with tableName + schema produces both arguments") {
            val app =
                applied(
                    "Entity",
                    mapOf(
                        "tableName" to TagValue.StringVal("users"),
                        "schema" to TagValue.StringVal("auth"),
                    ),
                )
            JavaStereotypeMapper.toAnnotation(app) shouldBe
                "@jakarta.persistence.Entity(name = \"users\", schema = \"auth\")"
        }

        test("Entity without tags produces bare @Entity") {
            JavaStereotypeMapper.toAnnotation(applied("Entity")) shouldBe "@jakarta.persistence.Entity"
        }

        test("Id stereotype produces @Id") {
            JavaStereotypeMapper.toAnnotation(applied("Id")) shouldBe "@jakarta.persistence.Id"
        }

        test("Column with name and nullable") {
            val app =
                applied(
                    "Column",
                    mapOf(
                        "name" to TagValue.StringVal("email_address"),
                        "nullable" to TagValue.BoolVal(false),
                    ),
                )
            JavaStereotypeMapper.toAnnotation(app) shouldBe
                "@jakarta.persistence.Column(name = \"email_address\", nullable = false)"
        }

        test("Transient produces @Transient") {
            JavaStereotypeMapper.toAnnotation(applied("Transient")) shouldBe
                "@jakarta.persistence.Transient"
        }

        test("unknown stereotype returns null") {
            JavaStereotypeMapper.toAnnotation(applied("Unknown")) shouldBe null
        }

        test("isExcluded returns true for Transient") {
            JavaStereotypeMapper.isExcluded(listOf(applied("Transient"))) shouldBe true
        }

        test("isExcluded returns true for Internal") {
            JavaStereotypeMapper.isExcluded(listOf(applied("Internal"))) shouldBe true
        }

        test("isExcluded returns false for Entity / Id") {
            JavaStereotypeMapper.isExcluded(listOf(applied("Entity"), applied("Id"))) shouldBe false
        }
    })
