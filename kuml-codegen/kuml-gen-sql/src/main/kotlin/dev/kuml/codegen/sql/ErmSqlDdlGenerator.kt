package dev.kuml.codegen.sql

import dev.kuml.codegen.api.ErmCodeGenerator
import dev.kuml.codegen.api.ErmCodeGeneratorProvider
import dev.kuml.erm.model.ErmModel
import java.io.File

/**
 * V3.4.7 — SQL DDL Generator, ERM-first entry point.
 *
 * Equivalent to [SqlDdlGenerator] (the UML-direct path), but consumes a typed
 * [ErmModel] directly instead of chaining through `UmlToErmTransformer` first —
 * for `.kuml.kts` scripts that already build an `ermModel { … }` themselves.
 * Both entry points delegate all DDL rendering to [ErmSqlEmitter]; this class
 * is a thin file writer.
 *
 * Optionen via `options`-Map (identical to [SqlDdlGenerator]):
 *  - `sql-dialect` — `postgres` (default) / `mysql` / `h2` / `sqlite`
 *  - `sql-drop`    — `true` für einen führenden `DROP TABLE`/`DROP VIEW`-Block (default false)
 *
 * Schreibt eine einzelne Datei `schema.sql`.
 */
public class ErmSqlDdlGenerator : ErmCodeGenerator {
    override val id: String = "sql"
    override val displayName: String = "SQL DDL (PostgreSQL / MySQL / H2 / SQLite) — ERM input"

    override fun generate(
        model: ErmModel,
        outputDir: File,
        options: Map<String, String>,
    ): List<File> {
        outputDir.mkdirs()
        val dialect = SqlDialect.from(options["sql-dialect"] ?: "postgres")
        val ddl = ErmSqlEmitter(dialect, SqlEmitOptions.from(options)).emit(model)

        val file = File(outputDir, "schema.sql")
        file.writeText(ddl)
        return listOf(file)
    }
}

/** ServiceLoader provider for [ErmSqlDdlGenerator]. */
internal class ErmSqlDdlGeneratorProvider : ErmCodeGeneratorProvider {
    override fun generator(): ErmCodeGenerator = ErmSqlDdlGenerator()
}
