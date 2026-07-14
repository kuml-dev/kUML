package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.api.CodeGenerationException
import dev.kuml.codegen.api.ErmCodeGenerator
import dev.kuml.codegen.api.ErmCodeGeneratorProvider
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.erm.model.ErmModel
import java.io.File

/**
 * V3.4.8 — Kotlin Exposed `Table` object generator, ERM-first entry point.
 *
 * Equivalent to [ErmToExposedTransformer] (the M2M-chain path), but implements
 * [ErmCodeGenerator] so it can be addressed by id from the CLI/MCP layer —
 * `kuml generate --plugin exposed -i schema.erm.kuml.kts -o out/` — for
 * `.kuml.kts` scripts that already build an `ermModel { … }` themselves,
 * without going through `UmlToErmTransformer` first. Mirrors
 * `dev.kuml.codegen.sql.ErmSqlDdlGenerator` (V3.4.7's equivalent for SQL DDL)
 * one-to-one.
 *
 * All rendering logic lives in [ErmExposedEmitter]; this class is a thin file
 * writer.
 *
 * Options via `options`-Map:
 *  - `package` — Kotlin package for the generated `Table` objects (default
 *    `"com.example.tables"`).
 *  - `uuidRepresentation` — which Kotlin type `ErmDataType.Uuid` columns render as: `"java"`
 *    (default, `Column<java.util.UUID>`) or `"kotlin"` (`Column<kotlin.uuid.Uuid>`, Exposed
 *    1.x native support). See [UuidRepresentation] and [ErmExposedEmitter]'s KDoc.
 *  - `dateTimeRepresentation` — which Kotlin type `ErmDataType.Date`/`ErmDataType.Timestamp`
 *    columns render as: `"java"` (default, `Column<java.time.LocalDate>`/
 *    `Column<java.time.LocalDateTime>`) or `"kotlin"` (`Column<kotlinx.datetime.LocalDate>`/
 *    `Column<kotlinx.datetime.LocalDateTime>`, Exposed 1.x's kotlinx-datetime module).
 *    Independent of `uuidRepresentation`. See [DateTimeRepresentation] and
 *    [ErmExposedEmitter]'s KDoc.
 *
 * Writes one file per [ErmModel] entity, named `"<ObjectName>.kt"`.
 */
public class ErmExposedGenerator : ErmCodeGenerator {
    override val id: String = "exposed"
    override val displayName: String = "Kotlin Exposed Table objects — ERM input"

    override fun generate(
        model: ErmModel,
        outputDir: File,
        options: Map<String, String>,
    ): List<File> {
        outputDir.mkdirs()
        val packageName = options["package"] ?: ErmExposedEmitter.DEFAULT_PACKAGE
        val uuidRepresentation = UuidRepresentation.fromOption(options["uuidRepresentation"])
        val dateTimeRepresentation = DateTimeRepresentation.fromOption(options["dateTimeRepresentation"])
        return when (
            val result =
                ErmExposedEmitter(packageName, uuidRepresentation, dateTimeRepresentation).emit(model)
        ) {
            is TransformResult.Success ->
                result.output.map { file ->
                    File(outputDir, file.relativePath).apply {
                        parentFile?.mkdirs()
                        writeText(file.content)
                    }
                }
            is TransformResult.Failure ->
                throw CodeGenerationException(
                    "erm-to-exposed: " + result.errors.joinToString("; ") { it.message },
                )
        }
    }
}

/** ServiceLoader provider for [ErmExposedGenerator]. */
internal class ErmExposedGeneratorProvider : ErmCodeGeneratorProvider {
    override fun generator(): ErmCodeGenerator = ErmExposedGenerator()
}
