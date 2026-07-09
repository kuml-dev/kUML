package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlDiagram

/**
 * CLI-runnable wrapper around [UmlToErmTransformer].
 *
 * kUML's CLI `TransformCommand` can only execute transformers whose output is
 * `List<GeneratedFile>` (the standard UML transform path) — a transformer
 * producing a raw [dev.kuml.erm.model.ErmModel] cannot be run directly by the
 * CLI (see [UmlToErmTransformer]'s KDoc, "known limitations"). This wrapper
 * runs [UmlToErmTransformer] and renders its output with [ErmScriptRenderer]
 * into a single renderable `*.erm.kuml.kts` file, so the result is directly
 * viewable via `kuml render out/xyz.erm.kuml.kts --notation martin` without
 * any CLI changes.
 *
 * Transformer id: `"uml-to-erm-script"` — usable via
 * `kuml transform --transformer uml-to-erm-script`.
 *
 * V3.4.6
 */
public class UmlToErmScriptTransformer : KumlTransformer<KumlDiagram, List<GeneratedFile>> {
    override val id: String = "uml-to-erm-script"
    override val description: String =
        "UML class diagram (PIM) → renderable ERM diagram script (.erm.kuml.kts)"

    private val core = UmlToErmTransformer()

    override fun transform(
        source: KumlDiagram,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> =
        when (val result = core.transform(source, ctx)) {
            is TransformResult.Success -> {
                val slug =
                    source.name
                        .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                        .trim()
                        .replace(" ", "-")
                        .lowercase()
                        .ifBlank { "model" }
                val file = GeneratedFile("$slug.erm.kuml.kts", ErmScriptRenderer.render(result.output))
                TransformResult.Success(listOf(file), result.trace)
            }
            is TransformResult.Failure -> result
        }
}

/** ServiceLoader provider for [UmlToErmScriptTransformer]. */
public class UmlToErmScriptTransformerProvider : KumlTransformerProvider {
    override fun transformer(): UmlToErmScriptTransformer = UmlToErmScriptTransformer()
}
