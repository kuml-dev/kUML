package dev.kuml.desktop.ai

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.uml.dsl.print.UmlModelDslPrinter

/**
 * Converts an [AnyKumlModel] to a *.kuml.kts DSL string suitable for the editor.
 * V3.0.25: UML only. C4 + SysML2 printers deferred to V3.0.26.
 */
object ScriptSerializer {
    fun toDsl(model: AnyKumlModel): String =
        when (model) {
            is AnyKumlModel.Uml -> UmlModelDslPrinter.print(model.toKumlModel())
            is AnyKumlModel.C4 -> "// TODO V3.0.26: C4DslPrinter not yet implemented\n"
            is AnyKumlModel.Sysml2 -> "// TODO V3.0.26: Sysml2DslPrinter not yet implemented\n"
        }
}
