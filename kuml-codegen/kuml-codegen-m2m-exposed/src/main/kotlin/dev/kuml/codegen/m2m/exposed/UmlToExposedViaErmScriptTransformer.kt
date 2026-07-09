package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TransformChain
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.transform.umlerm.UmlToErmTransformer

/**
 * V3.4.8 — CLI-runnable chain: UML class diagram (PIM) → [ErmModel] → Kotlin
 * Exposed `Table` object source files.
 *
 * Wraps a [TransformChain] of `UmlToErmTransformer` (`"uml-to-erm"`) followed
 * by [ErmToExposedTransformer] (`"erm-to-exposed"`). Because both steps are
 * [KumlTransformer]s, the CLI's standard `TransformCommand` can run this
 * end-to-end with no CLI changes:
 *
 * ```
 * kuml transform -t uml-to-exposed-via-erm diagram.kuml.kts -o out/
 * ```
 *
 * Unlike the older, still-supported [UmlToExposedTransformer] (Variante B,
 * which derives Exposed columns directly from UML properties/associations),
 * many-to-many associations flow through `UmlToErmTransformer`'s junction-table
 * materialization first, so this path emits genuine junction `Table` objects
 * with a composite primary key instead of a `// *-to-many not represented`
 * comment.
 *
 * `--package` and `idType` options are threaded through
 * [dev.kuml.codegen.m2m.TransformContext.options] to both chain steps —
 * `idType` is consumed by `UmlToErmTransformer` (synthetic primary-key column
 * type when a class has no explicit id), `package` is consumed by
 * [ErmToExposedTransformer] (Kotlin package of the generated files).
 */
public class UmlToExposedViaErmScriptTransformer : KumlTransformer<KumlDiagram, List<GeneratedFile>> {
    override val id: String = "uml-to-exposed-via-erm"
    override val description: String =
        "UML class diagram → ERM → Kotlin Exposed Table objects (chained uml-to-erm + erm-to-exposed)"

    private val chain = TransformChain(UmlToErmTransformer(), ErmToExposedTransformer())

    override fun transform(
        source: KumlDiagram,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> = chain.transform(source, ctx)
}

/** ServiceLoader provider for [UmlToExposedViaErmScriptTransformer]. */
public class UmlToExposedViaErmScriptTransformerProvider : KumlTransformerProvider {
    override fun transformer(): UmlToExposedViaErmScriptTransformer = UmlToExposedViaErmScriptTransformer()
}
