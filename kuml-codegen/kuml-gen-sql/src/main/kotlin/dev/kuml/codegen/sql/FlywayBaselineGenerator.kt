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
 * Flyways eigene Namenskonvention validiert ([FlywayFileNaming]), bevor sie
 * in den Dateinamen interpoliert werden. Damit sind Pfad-Traversal-Versuche
 * (`..`, `/`, `\`) über diese Optionen ausgeschlossen. Zusätzlich wird vor dem
 * Schreiben verifiziert, dass die resultierende Datei kanonisch ein direktes
 * Kind von `outputDir` ist.
 *
 * Funktioniert unverändert mit jedem [KumlDiagram], das [SqlDdlGenerator]
 * akzeptiert — inklusive dem V3.4.7 UML→ERM-Chain (`UmlToErmTransformer`) und
 * dem älteren Wave-A-PSM aus `UmlToExposedPsmTransformer` (dessen dual-apply
 * `«Entity»`-Stereotyp-Workaround seit V3.4.7 kein Sonderfall mehr ist, weil
 * das ERM-Modell ohnehin per Naming-Konvention denselben Tabellennamen
 * ableitet, sofern kein ERM-`«Entity»`-Stereotyp gesetzt ist).
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
        val migrationFile = FlywayFileNaming.resolveMigrationFile(outputDir, version, description)

        // Delegate into a scratch subdirectory so SqlDdlGenerator's fixed
        // "schema.sql" never touches outputDir directly — avoids collisions
        // and guarantees no leftover schema.sql remains in outputDir.
        val scratchDir = File(outputDir, ".flyway-baseline-scratch")
        val delegateFiles = delegate.generate(diagram, scratchDir, options)
        check(delegateFiles.size == 1) {
            "FlywayBaselineGenerator expects SqlDdlGenerator to produce exactly one file, got ${delegateFiles.size}"
        }
        val content = delegateFiles.single().readText()

        migrationFile.writeText(content)

        scratchDir.deleteRecursively()

        return listOf(migrationFile)
    }
}
