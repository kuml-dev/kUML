package dev.kuml.codegen.api

import dev.kuml.core.model.KumlDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * V1.1.4 Ticket 1 — Codegen ServiceLoader registry.
 */
class CodeGenRegistryTest :
    FunSpec({

        beforeEach { CodeGenRegistry.clear() }
        afterSpec { CodeGenRegistry.clear() }

        // ── Tiny test fixture ────────────────────────────────────────────────
        class FakeGenerator(
            override val id: String,
        ) : KumlCodeGenerator {
            override val displayName: String = "Fake $id"

            override fun generate(
                diagram: KumlDiagram,
                outputDir: File,
                options: Map<String, String>,
            ): List<File> = emptyList()
        }

        class FakeProvider(
            val gen: KumlCodeGenerator,
        ) : KumlCodeGeneratorProvider {
            override fun generator(): KumlCodeGenerator = gen
        }

        test("register and get by id") {
            CodeGenRegistry.register(FakeProvider(FakeGenerator("test-id")))
            val gen = CodeGenRegistry.get("test-id")
            gen shouldNotBe null
            gen!!.id shouldBe "test-id"
        }

        test("get returns null for unknown id") {
            CodeGenRegistry.get("unknown") shouldBe null
        }

        test("names lists all registered ids in sorted order") {
            CodeGenRegistry.register(FakeProvider(FakeGenerator("b")))
            CodeGenRegistry.register(FakeProvider(FakeGenerator("a")))
            CodeGenRegistry.register(FakeProvider(FakeGenerator("c")))
            CodeGenRegistry.names() shouldBe listOf("a", "b", "c")
        }

        test("loadFromClasspath discovers the bundled kotlin provider") {
            CodeGenRegistry.loadFromClasspath()
            CodeGenRegistry.names() shouldContain "kotlin"
        }

        test("clear empties the registry") {
            CodeGenRegistry.register(FakeProvider(FakeGenerator("x")))
            CodeGenRegistry.names().isNotEmpty() shouldBe true
            CodeGenRegistry.clear()
            CodeGenRegistry.names().isEmpty() shouldBe true
        }
    })
