package dev.kuml.codegen.sql

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.core.model.KumlDiagram
import java.io.File

/**
 * ADR-0016 Entscheidung 3 — Flyway-Baseline-Wrapper um [SqlDdlGenerator].
 *
 * Erzeugt keine eigene DDL-Logik, sondern delegiert vollständig an
 * [SqlDdlGenerator] (Composition, keine Duplikation) und schreibt das
 * Ergebnis unter dem Flyway-Namensschema `V<version>__<description>.sql`
 * statt des generischen `schema.sql`.
 *
 * Optionen via `options`-Map (zusätzlich zu allen von [SqlDdlGenerator]
 * unterstützten Optionen, die unverändert durchgereicht werden):
 *  - `flyway-version`     — Versionspräfix, default `"1"`   → `V1`
 *  - `flyway-description` — Beschreibung, default `"init"`  → `__init`
 *
 * Ergebnisdatei bei Default-Optionen: `V1__init.sql`.
 *
 * Sicherheit: `flyway-version` und `flyway-description` werden gegen
 * Flyways eigene Namenskonvention validiert (siehe [VERSION_PATTERN] /
 * [DESCRIPTION_PATTERN]), bevor sie in den Dateinamen interpoliert werden.
 * Damit sind Pfad-Traversal-Versuche (`..`, `/`, `\`) über diese Optionen
 * ausgeschlossen. Zusätzlich wird vor dem Schreiben verifiziert, dass die
 * resultierende Datei kanonisch ein direktes Kind von `outputDir` ist.
 *
 * Funktioniert unverändert sowohl mit dem rohen PIM (naive Pluralisierung
 * via [SqlNames]) als auch mit dem Wave-A-PSM aus
 * `UmlToExposedPsmTransformer` (explizite Tabellennamen über das
 * dual-applied `«Entity»`-Stereotyp) — für [SqlDdlGenerator] ist beides
 * einfach ein [KumlDiagram].
 */
public class FlywayBaselineGenerator(
    private val delegate: KumlCodeGenerator = SqlDdlGenerator(),
) : KumlCodeGenerator {
    override val id: String = "sql-flyway-baseline"
    override val displayName: String = "Flyway Baseline Migration (V1__init.sql)"

    override fun generate(
        diagram: KumlDiagram,
        outputDir: File,
        options: Map<String, String>,
    ): List<File> {
        outputDir.mkdirs()
        val version = options["flyway-version"] ?: "1"
        val description = options["flyway-description"] ?: "init"

        require(VERSION_PATTERN.matches(version)) {
            "Invalid flyway-version '$version' — must match ${VERSION_PATTERN.pattern} " +
                "(Flyway version naming convention, e.g. \"1\" or \"1.2.1\")"
        }
        require(DESCRIPTION_PATTERN.matches(description)) {
            "Invalid flyway-description '$description' — must match ${DESCRIPTION_PATTERN.pattern} " +
                "(letters, digits, underscores only — no path separators or '..')"
        }

        // Delegate into a scratch subdirectory so SqlDdlGenerator's fixed
        // "schema.sql" never touches outputDir directly — avoids collisions
        // and guarantees no leftover schema.sql remains in outputDir.
        val scratchDir = File(outputDir, ".flyway-baseline-scratch")
        val delegateFiles = delegate.generate(diagram, scratchDir, options)
        check(delegateFiles.size == 1) {
            "FlywayBaselineGenerator expects SqlDdlGenerator to produce exactly one file, got ${delegateFiles.size}"
        }
        val content = delegateFiles.single().readText()

        val migrationFile = File(outputDir, "V${version}__$description.sql")

        // Defense in depth: even though version/description are validated
        // above, verify the resulting file is still a direct child of
        // outputDir before writing.
        val canonicalOutputDir = outputDir.canonicalFile
        val canonicalMigrationFile = migrationFile.canonicalFile
        check(canonicalMigrationFile.parentFile == canonicalOutputDir) {
            "Resolved migration file '$canonicalMigrationFile' escapes outputDir '$canonicalOutputDir'"
        }

        migrationFile.writeText(content)

        scratchDir.deleteRecursively()

        return listOf(migrationFile)
    }

    private companion object {
        val VERSION_PATTERN = Regex("^[0-9]+(\\.[0-9]+)*$")
        val DESCRIPTION_PATTERN = Regex("^[A-Za-z0-9_]+$")
    }
}
