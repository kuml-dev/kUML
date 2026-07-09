package dev.kuml.codegen.api

import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * V3.4.7 — ERM-first codegen ServiceLoader registry. Mirrors [CodeGenRegistryTest]
 * (loadFromClasspath discovery of the bundled `sql` generator is covered in
 * `kuml-gen-sql`'s own test suite — that is where the actual provider lives).
 */
class ErmCodeGenRegistryTest :
    FunSpec({

        beforeEach { ErmCodeGenRegistry.clear() }
        afterSpec { ErmCodeGenRegistry.clear() }

        class FakeGenerator(
            override val id: String,
        ) : ErmCodeGenerator {
            override val displayName: String = "Fake $id"

            override fun generate(
                model: ErmModel,
                outputDir: File,
                options: Map<String, String>,
            ): List<File> = emptyList()
        }

        class FakeProvider(
            val gen: ErmCodeGenerator,
        ) : ErmCodeGeneratorProvider {
            override fun generator(): ErmCodeGenerator = gen
        }

        test("register and get by id") {
            ErmCodeGenRegistry.register(FakeProvider(FakeGenerator("test-id")))
            val gen = ErmCodeGenRegistry.get("test-id")
            gen shouldNotBe null
            gen!!.id shouldBe "test-id"
        }

        test("get returns null for unknown id") {
            ErmCodeGenRegistry.get("unknown") shouldBe null
        }

        test("names lists all registered ids in sorted order") {
            ErmCodeGenRegistry.register(FakeProvider(FakeGenerator("b")))
            ErmCodeGenRegistry.register(FakeProvider(FakeGenerator("a")))
            ErmCodeGenRegistry.register(FakeProvider(FakeGenerator("c")))
            ErmCodeGenRegistry.names() shouldBe listOf("a", "b", "c")
        }

        test("clear empties the registry") {
            ErmCodeGenRegistry.register(FakeProvider(FakeGenerator("x")))
            ErmCodeGenRegistry.names().isNotEmpty() shouldBe true
            ErmCodeGenRegistry.clear()
            ErmCodeGenRegistry.names().isEmpty() shouldBe true
        }
    })
