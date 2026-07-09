package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.erm.registry.ErmReverseEngineRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Verifies ServiceLoader discovery of [PostgresErmReverseEngine] via `META-INF/services`. */
class SqlReverseEngineRegistryTest :
    FunSpec({

        test("all() finds the sql-postgres engine on this module's classpath") {
            val engines = ErmReverseEngineRegistry.all()
            engines.map { it.id } shouldBe listOf("sql-postgres")
        }

        test("byId(\"sql-postgres\") resolves the engine") {
            val engine = ErmReverseEngineRegistry.byId("sql-postgres")
            engine.shouldBeInstanceOf<PostgresErmReverseEngine>()
        }

        test("byDialect(\"postgres\") resolves the same engine") {
            val engine = ErmReverseEngineRegistry.byDialect("postgres")
            engine.shouldBeInstanceOf<PostgresErmReverseEngine>()
            engine.id shouldBe "sql-postgres"
        }

        test("byDialect of an unknown dialect returns null") {
            ErmReverseEngineRegistry.byDialect("mysql") shouldBe null
        }
    })
