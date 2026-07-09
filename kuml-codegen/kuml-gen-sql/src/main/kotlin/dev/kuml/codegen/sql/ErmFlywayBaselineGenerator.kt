package dev.kuml.codegen.sql

import dev.kuml.codegen.api.ErmCodeGenerator
import dev.kuml.codegen.api.ErmCodeGeneratorProvider
import dev.kuml.erm.model.ErmModel
import java.io.File

/**
 * V3.4.7 — ERM-first counterpart to [FlywayBaselineGenerator].
 *
 * Delegates to [ErmSqlDdlGenerator] (composition, no DDL logic of its own) and
 * writes the result under Flyway's `V<version>__<description>.sql` naming
 * scheme instead of the generic `schema.sql` — see [FlywayFileNaming] for the
 * shared validation/path-traversal guards this shares with the UML-direct
 * [FlywayBaselineGenerator].
 *
 * Optionen via `options`-Map (identisch zu [FlywayBaselineGenerator], zusätzlich
 * zu allen von [ErmSqlDdlGenerator] unterstützten Optionen):
 *  - `flyway-version`     — Versionspräfix, default `"1"`   → `V1`
 *  - `flyway-description` — Beschreibung, default `"init"`  → `__init`
 */
public class ErmFlywayBaselineGenerator(
    private val delegate: ErmCodeGenerator = ErmSqlDdlGenerator(),
) : ErmCodeGenerator {
    override val id: String = "sql-flyway-baseline"
    override val displayName: String = "Flyway Baseline Migration (V1__init.sql) — ERM input"

    override fun generate(
        model: ErmModel,
        outputDir: File,
        options: Map<String, String>,
    ): List<File> {
        outputDir.mkdirs()
        val version = options["flyway-version"] ?: "1"
        val description = options["flyway-description"] ?: "init"
        val migrationFile = FlywayFileNaming.resolveMigrationFile(outputDir, version, description)

        val scratchDir = File(outputDir, ".flyway-baseline-scratch")
        val delegateFiles = delegate.generate(model, scratchDir, options)
        check(delegateFiles.size == 1) {
            "ErmFlywayBaselineGenerator expects ErmSqlDdlGenerator to produce exactly one file, got ${delegateFiles.size}"
        }
        val content = delegateFiles.single().readText()

        migrationFile.writeText(content)

        scratchDir.deleteRecursively()

        return listOf(migrationFile)
    }
}

/** ServiceLoader provider for [ErmFlywayBaselineGenerator]. */
internal class ErmFlywayBaselineGeneratorProvider : ErmCodeGeneratorProvider {
    override fun generator(): ErmCodeGenerator = ErmFlywayBaselineGenerator()
}
