package dev.kuml.codegen.sql

import java.io.File

/**
 * V3.4.7 — shared Flyway migration-filename validation/resolution, extracted
 * out of [FlywayBaselineGenerator] so [ErmFlywayBaselineGenerator] (the
 * ERM-first counterpart) can reuse the exact same path-traversal guards
 * without duplicating them.
 *
 * Security: `version`/`description` are validated against Flyway's own naming
 * convention ([VERSION_PATTERN] / [DESCRIPTION_PATTERN]) before being
 * interpolated into a file name, ruling out path-traversal attempts (`..`,
 * `/`, `\`) via these options. [resolveMigrationFile] additionally verifies —
 * defense in depth — that the resolved file is still a direct canonical child
 * of `outputDir` before the caller writes to it.
 */
internal object FlywayFileNaming {
    private val VERSION_PATTERN = Regex("^[0-9]+(\\.[0-9]+)*$")
    private val DESCRIPTION_PATTERN = Regex("^[A-Za-z0-9_]+$")

    /** @throws IllegalArgumentException if [version]/[description] don't match Flyway's naming convention. */
    fun validate(
        version: String,
        description: String,
    ) {
        require(VERSION_PATTERN.matches(version)) {
            "Invalid flyway-version '$version' — must match ${VERSION_PATTERN.pattern} " +
                "(Flyway version naming convention, e.g. \"1\" or \"1.2.1\")"
        }
        require(DESCRIPTION_PATTERN.matches(description)) {
            "Invalid flyway-description '$description' — must match ${DESCRIPTION_PATTERN.pattern} " +
                "(letters, digits, underscores only — no path separators or '..')"
        }
    }

    /**
     * Resolves the `V<version>__<description>.sql` file under [outputDir], after
     * [validate]ing both components. Verifies the resolved file is still a direct
     * canonical child of [outputDir] before returning it.
     */
    fun resolveMigrationFile(
        outputDir: File,
        version: String,
        description: String,
    ): File {
        validate(version, description)

        val migrationFile = File(outputDir, "V${version}__$description.sql")

        val canonicalOutputDir = outputDir.canonicalFile
        val canonicalMigrationFile = migrationFile.canonicalFile
        check(canonicalMigrationFile.parentFile == canonicalOutputDir) {
            "Resolved migration file '$canonicalMigrationFile' escapes outputDir '$canonicalOutputDir'"
        }
        return migrationFile
    }
}
