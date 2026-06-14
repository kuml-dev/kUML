package dev.kuml.codegen.reverse.kotlin

import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.codegen.reverse.registry.ReverseEngineRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class KotlinSourceReverseEngineTest :
    FunSpec({

        test("engine id is kotlin") {
            KotlinSourceReverseEngine().id shouldBe "kotlin"
        }

        test("engine is discoverable via ReverseEngineRegistry") {
            val engine = ReverseEngineRegistry.byId("kotlin")
            engine shouldNotBe null
            engine!!.id shouldBe "kotlin"
        }

        test("empty source root returns failure with REV-CORE-001") {
            val emptyDir = Files.createTempDirectory("kuml-empty-")
            val request =
                ReverseRequest(
                    sourceRoots = listOf(emptyDir),
                    targetModelName = "Empty",
                )
            val result = runBlocking { KotlinSourceReverseEngine().analyze(request) }
            result.shouldBeInstanceOf<ReverseResult.Failure>()
            val failure = result as ReverseResult.Failure
            failure.errors.any { it.code == "REV-CORE-001" } shouldBe true
        }
    })
