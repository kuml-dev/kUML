package dev.kuml.codegen.sql

import dev.kuml.codegen.api.CodeGenerationException
import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.erm.model.ErmModel
import dev.kuml.transform.umlerm.UmlToErmTransformer
import java.io.File

/**
 * V3.4.7 — SQL DDL Generator, UML-direct entry point.
 *
 * Chains a UML class diagram through `UmlToErmTransformer` ("uml-to-erm",
 * `kuml-transform-uml-to-erm`) into a typed [ErmModel], then delegates all DDL
 * rendering to [ErmSqlEmitter] — the single source of truth for SQL generation
 * shared with the ERM-first entry point [ErmSqlDdlGenerator]. This class itself
 * contains no DDL logic anymore (V1.1.4–V3.4.6's hand-rolled topo-sort /
 * `CREATE TABLE` / FK / enum rendering has moved to [ErmSqlEmitter], operating
 * on [ErmModel] instead of raw UML).
 *
 * Optionen via `options`-Map:
 *  - `sql-dialect` — `postgres` (default) / `mysql` / `h2` / `sqlite`
 *  - `sql-drop`    — `true` für einen führenden `DROP TABLE`/`DROP VIEW`-Block (default false)
 *  - `idType`      — `uuid` / `int` / `integer` (default bigint) — durchgereicht an `UmlToErmTransformer`
 *    für die synthetische Primary-Key-Spalte, wenn eine Klasse keine eigene `id` hat.
 *
 * Schreibt eine einzelne Datei `schema.sql`.
 *
 * Stereotyp-aware über das ERM-Mapping-Profil (`dev.kuml.profile.erm.ermMappingProfile`):
 * `«Entity»{tableName=…}` setzt den Tabellennamen, `«Column»{columnName=…}` den
 * Spaltennamen, `«Id»` den Primary Key, `«Transient»` schließt Properties aus —
 * siehe [UmlToErmTransformer] für die vollständige Mapping-Referenz.
 *
 * ### Verhaltensänderungen gegenüber V1.1.4–V3.4.6 (bewusst, siehe V3.4.7-Plan §7)
 * - Enum-Properties werden nicht mehr als Postgres `CREATE TYPE … AS ENUM` emittiert,
 *   sondern als `VARCHAR` + `CHECK (col IN (…))` — `UmlToErmTransformer`s Enum-Semantik.
 * - FK-Spalten stehen jetzt direkt in `CREATE TABLE` (nicht mehr `ALTER TABLE … ADD COLUMN`);
 *   nur der Constraint kommt weiterhin per `ALTER TABLE … ADD CONSTRAINT`.
 * - M:N-Assoziationen erzeugen eine echte Junction-Table mit Composite-PK statt eines
 *   `-- TODO`-Kommentars.
 * - Indizes, Views, Check-Constraints und referentielle Aktionen (`ON DELETE`/`ON UPDATE`)
 *   werden emittiert, wo das ERM-Modell sie trägt.
 */
public class SqlDdlGenerator : KumlCodeGenerator {
    override val id: String = "sql"
    override val displayName: String = "SQL DDL (PostgreSQL / MySQL / H2 / SQLite)"

    override fun generate(
        diagram: KumlDiagram,
        outputDir: File,
        options: Map<String, String>,
    ): List<File> {
        outputDir.mkdirs()

        val model: ErmModel =
            when (val result = UmlToErmTransformer().transform(diagram, TransformContext(options))) {
                is TransformResult.Success -> result.output
                is TransformResult.Failure ->
                    throw CodeGenerationException(
                        "kuml-gen-sql: UML→ERM step failed: " +
                            result.errors.joinToString("; ") { it.message },
                    )
            }

        val dialect = SqlDialect.from(options["sql-dialect"] ?: "postgres")
        val ddl = ErmSqlEmitter(dialect, SqlEmitOptions.from(options)).emit(model)

        val file = File(outputDir, "schema.sql")
        file.writeText(ddl)
        return listOf(file)
    }
}
