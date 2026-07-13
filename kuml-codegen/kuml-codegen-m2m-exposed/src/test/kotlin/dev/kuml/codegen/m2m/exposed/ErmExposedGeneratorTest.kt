package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.api.CodeGenerationException
import dev.kuml.codegen.api.ErmCodeGenRegistry
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * V3.4.8 — [ErmExposedGenerator] (ERM-first CLI/plugin entry point, `--plugin exposed`)
 * and its [ErmCodeGenRegistry] registration.
 */
class ErmExposedGeneratorTest :
    FunSpec({

        val generator = ErmExposedGenerator()

        test("id and displayName") {
            generator.id shouldBe "exposed"
            generator.displayName shouldContain "Exposed"
        }

        test("writes one file per entity to the output directory") {
            val model =
                ermModel("M") {
                    entity("users") { id("id", ErmDataType.Integer(64)) }
                    entity("products") { id("id", ErmDataType.Integer(64)) }
                }
            val out = Files.createTempDirectory("kuml-erm-exposed-test").toFile()
            try {
                val files = generator.generate(model, out, emptyMap())
                files shouldHaveSize 2
                files.map { it.name }.toSet() shouldBe setOf("Users.kt", "Products.kt")
                files.forEach { it.readText() shouldContain "public object" }
            } finally {
                out.deleteRecursively()
            }
        }

        test("--package option is honored") {
            val model = ermModel("M") { entity("users") { id("id", ErmDataType.Integer(64)) } }
            val out = Files.createTempDirectory("kuml-erm-exposed-test").toFile()
            try {
                val files = generator.generate(model, out, mapOf("package" to "org.myapp.tables"))
                files.single().readText() shouldContain "package org.myapp.tables"
            } finally {
                out.deleteRecursively()
            }
        }

        test("invalid model throws CodeGenerationException instead of writing files") {
            val model = ermModel("Empty") {}
            val out = Files.createTempDirectory("kuml-erm-exposed-test").toFile()
            try {
                shouldThrow<CodeGenerationException> { generator.generate(model, out, emptyMap()) }
            } finally {
                out.deleteRecursively()
            }
        }

        test("creates the output directory if absent") {
            val model = ermModel("M") { entity("users") { id("id", ErmDataType.Integer(64)) } }
            val parent = Files.createTempDirectory("kuml-erm-exposed-test").toFile()
            val out = parent.resolve("nested/does/not/exist")
            try {
                out.exists() shouldBe false
                val files = generator.generate(model, out, emptyMap())
                out.exists() shouldBe true
                files shouldHaveSize 1
            } finally {
                parent.deleteRecursively()
            }
        }

        test("kotlinObjectName override is honored through this entry point") {
            val model =
                ermModel("M") {
                    entity("member") {
                        kotlinObjectName("MemberTable")
                        id("id", ErmDataType.Integer(64))
                    }
                }
            val out = Files.createTempDirectory("kuml-erm-exposed-test").toFile()
            try {
                val files = generator.generate(model, out, emptyMap())
                files.map { it.name } shouldBe listOf("MemberTable.kt")
                files.single().readText() shouldContain "public object MemberTable : Table(\"member\")"
            } finally {
                out.deleteRecursively()
            }
        }

        test("provider is exposed as 'exposed' generator id in the ErmCodeGenRegistry") {
            ErmExposedGeneratorProvider().generator().id shouldBe "exposed"
        }

        test("loadFromClasspath discovers the bundled ERM exposed generator") {
            ErmCodeGenRegistry.clear()
            ErmCodeGenRegistry.loadFromClasspath()
            ErmCodeGenRegistry.names() shouldContain "exposed"
        }
    })
