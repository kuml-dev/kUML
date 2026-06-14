package dev.kuml.codegen.reverse.registry

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ReverseEngineRegistryTest :
    FunSpec({

        test("all returns engines registered via ServiceLoader") {
            // The api module's service file is empty — no engine registered in this module.
            // When reverse-java is on the classpath (e.g. in end-to-end tests) it registers
            // itself. Here we only verify that the registry can be called without error.
            val engines = ReverseEngineRegistry.all()
            // engines may be empty in unit-test scope (only api jar on classpath)
            engines.forEach { engine ->
                engine.id.isNotBlank() shouldBe true
                engine.description.isNotBlank() shouldBe true
            }
        }

        test("byId returns null for unknown id") {
            ReverseEngineRegistry.byId("no-such-engine-xyz") shouldBe null
        }

        test("detectLanguage returns 'java' when source root contains .java majority") {
            val tmpDir = Files.createTempDirectory("rev-reg-test")
            try {
                // Create 3 .java files and 1 .kt file → java majority (75%)
                repeat(3) { i -> Files.writeString(tmpDir.resolve("File$i.java"), "// java") }
                Files.writeString(tmpDir.resolve("One.kt"), "// kotlin")
                val detected = ReverseEngineRegistry.detectLanguage(listOf(tmpDir))
                detected shouldBe "java"
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }

        test("detectLanguage returns null when no source files present") {
            val tmpDir = Files.createTempDirectory("rev-reg-empty")
            try {
                ReverseEngineRegistry.detectLanguage(listOf(tmpDir)) shouldBe null
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }
    })
