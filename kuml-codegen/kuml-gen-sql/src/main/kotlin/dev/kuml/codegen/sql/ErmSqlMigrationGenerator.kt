package dev.kuml.codegen.sql

import dev.kuml.codegen.api.CodeGenerationException
import dev.kuml.erm.constraint.ErmConstraintChecker
import dev.kuml.erm.constraint.ViolationSeverity
import dev.kuml.erm.model.ErmModel
import java.io.File

/**
 * ADR-0016 (deferred item) — additive-only SQL schema-diff migration
 * generator. Computes the delta between two [ErmModel] snapshots (`old` →
 * `new`) via [ErmSchemaDiffGenerator], refuses on any destructive or
 * ambiguous change (see [DiffOutcome.Refused]), and otherwise writes a single
 * Flyway-named migration file rendered by [ErmSchemaDiffEmitter].
 *
 * Not an [dev.kuml.codegen.api.ErmCodeGenerator] — that SPI's `generate`
 * consumes exactly one [ErmModel]; this generator inherently needs two. Called
 * directly by `kuml generate --sql-migration` (`GenerateCommand` in
 * `kuml-cli`) rather than dispatched by id through
 * [dev.kuml.codegen.api.ErmCodeGenRegistry] — no `META-INF/services` entry is
 * registered for it.
 *
 * Optionen via `options`-Map (identical to [ErmSqlDdlGenerator]):
 *  - `sql-dialect` — `postgres` (default) / `mysql` / `h2` / `sqlite`
 */
public class ErmSqlMigrationGenerator {
    /**
     * @param old The OLD ERM model snapshot (the schema the target database is currently at).
     * @param new The NEW ERM model snapshot (the schema to migrate towards).
     * @param outputDir Directory the migration file is written into. Created if absent.
     * @param version Flyway migration version, e.g. `"2"` — validated by [FlywayFileNaming].
     * @param description Flyway migration description, e.g. `"add_orders"` — validated by [FlywayFileNaming].
     * @param options Generator options (currently just `sql-dialect`).
     * @return The written migration [File].
     * @throws CodeGenerationException if either model fails [ErmConstraintChecker] validation, if the diff between
     *   them contains any destructive or ambiguous change (reasons are joined into the message), or if the diff is
     *   empty (no additive changes — refuses to write an empty migration file).
     * @throws IllegalArgumentException if [version]/[description] don't match Flyway's naming convention
     *   ([FlywayFileNaming.validate]).
     */
    public fun generate(
        old: ErmModel,
        new: ErmModel,
        outputDir: File,
        version: String,
        description: String,
        options: Map<String, String>,
    ): File {
        requireValid(old)
        requireValid(new)

        val diff =
            when (val outcome = ErmSchemaDiffGenerator.diff(old, new)) {
                is DiffOutcome.Refused ->
                    throw CodeGenerationException(
                        "kuml-gen-sql: refusing to generate an additive migration from '${old.name}' to " +
                            "'${new.name}' — destructive or ambiguous changes detected:\n" +
                            outcome.reasons.joinToString("\n") { "  - $it" },
                    )
                is DiffOutcome.Ok -> outcome.diff
            }

        if (diff.isEmpty) {
            throw CodeGenerationException(
                "kuml-gen-sql: no additive changes detected between '${old.name}' and '${new.name}' — " +
                    "refusing to write an empty migration.",
            )
        }

        val dialect = SqlDialect.from(options["sql-dialect"] ?: "postgres")
        val sql = ErmSchemaDiffEmitter(dialect, SqlEmitOptions.from(options)).emit(old, new, diff)

        // Nothing filesystem-visible happens above this line — a refused/empty diff (or a
        // model failing validation) never creates outputDir, let alone a migration file.
        outputDir.mkdirs()
        val migrationFile = FlywayFileNaming.resolveMigrationFile(outputDir, version, description)
        migrationFile.writeText(sql)
        return migrationFile
    }

    private fun requireValid(model: ErmModel) {
        val errors = ErmConstraintChecker().check(model).filter { it.severity == ViolationSeverity.ERROR }
        if (errors.isNotEmpty()) {
            throw CodeGenerationException(
                "kuml-gen-sql: ERM model '${model.name}' failed validation — refusing to compute a migration diff:\n" +
                    errors.joinToString("\n") { "  - ${it.message}" },
            )
        }
    }
}
