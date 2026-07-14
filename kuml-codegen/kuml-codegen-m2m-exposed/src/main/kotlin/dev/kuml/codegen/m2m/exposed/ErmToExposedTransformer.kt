package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.erm.model.ErmModel

/**
 * V3.4.8 — transforms a typed [ErmModel] directly into Kotlin Exposed `Table`
 * object source files.
 *
 * All rendering logic lives in [ErmExposedEmitter] (the single source of truth
 * shared with [ErmExposedGenerator]'s ERM-first CLI path and
 * [UmlToExposedViaErmScriptTransformer]'s chained UML-direct path); this class
 * is a thin [KumlTransformer] adapter.
 *
 * Not directly CLI-runnable (its source type is [ErmModel], not
 * [dev.kuml.core.model.KumlDiagram] — the same limitation as
 * `UmlToErmTransformer`), but usable programmatically or as the second half of
 * a [dev.kuml.codegen.m2m.TransformChain] — see
 * [UmlToExposedViaErmScriptTransformer]. Still registered via ServiceLoader for
 * parity with `UmlToErmTransformerProvider`.
 *
 * `--package` (`ctx.options["package"]`) selects the generated files' package
 * name (default `"com.example.tables"`). `uuidRepresentation`
 * (`ctx.options["uuidRepresentation"]`, `"java"` default / `"kotlin"`) selects which Kotlin
 * type `ErmDataType.Uuid` columns render as — see [UuidRepresentation] and
 * [ErmExposedEmitter]'s KDoc for the full rationale.
 */
public class ErmToExposedTransformer : KumlTransformer<ErmModel, List<GeneratedFile>> {
    override val id: String = "erm-to-exposed"
    override val description: String =
        "ERM model → Kotlin Exposed Table objects (Table, reference(), composite PK, junction tables)"

    override fun transform(
        source: ErmModel,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> =
        ErmExposedEmitter(
            packageName = ctx.options["package"] ?: ErmExposedEmitter.DEFAULT_PACKAGE,
            uuidRepresentation = UuidRepresentation.fromOption(ctx.options["uuidRepresentation"]),
        ).emit(source)
}

/** ServiceLoader provider for [ErmToExposedTransformer]. */
public class ErmToExposedTransformerProvider : KumlTransformerProvider {
    override fun transformer(): ErmToExposedTransformer = ErmToExposedTransformer()
}
