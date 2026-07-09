package dev.kuml.codegen.reverse.erm.registry

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ErmReverseEngineRegistryTest :
    FunSpec({

        test("all returns engines registered via ServiceLoader") {
            // The api module's service file is empty — no engine registered in this module.
            // When reverse-sql is on the classpath (e.g. in end-to-end tests) it registers
            // itself. Here we only verify that the registry can be called without error.
            val engines = ErmReverseEngineRegistry.all()
            engines.forEach { engine ->
                engine.id.isNotBlank() shouldBe true
                engine.dialect.isNotBlank() shouldBe true
                engine.description.isNotBlank() shouldBe true
            }
        }

        test("byId returns null for unknown id") {
            ErmReverseEngineRegistry.byId("no-such-engine-xyz") shouldBe null
        }

        test("byDialect returns null for unknown dialect") {
            ErmReverseEngineRegistry.byDialect("no-such-dialect-xyz") shouldBe null
        }
    })
