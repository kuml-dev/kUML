package dev.kuml.desktop.ai

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.c4.dsl.print.C4DslPrinter
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.uml.dsl.print.UmlModelDslPrinter

/**
 * Converts an [AnyKumlModel] to a *.kuml.kts DSL string suitable for the editor.
 *
 * All three model kinds are backed by a real printer: [UmlModelDslPrinter] for UML,
 * [C4DslPrinter] for C4, [Sysml2DslPrinter] for SysML 2. Each printer documents its own
 * known non-round-tripping fields in its class KDoc — see those for the C4/SysML2 specifics
 * (e.g. C4 element ids are never preserved since the C4 DSL has no `id =` parameter at all;
 * `ContainerDiagram`/`ComponentDiagram` are best-effort with a `// TODO` fallback when the
 * builder's coarse show*-flags / exclude() API cannot reach the exact original element subset).
 */
object ScriptSerializer {
    fun toDsl(model: AnyKumlModel): String =
        when (model) {
            is AnyKumlModel.Uml -> UmlModelDslPrinter.print(model.toKumlModel())
            is AnyKumlModel.C4 -> C4DslPrinter.print(model.model)
            is AnyKumlModel.Sysml2 -> Sysml2DslPrinter.print(model.model)
        }
}
