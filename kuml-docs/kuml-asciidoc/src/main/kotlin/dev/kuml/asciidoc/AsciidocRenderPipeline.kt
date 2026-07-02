package dev.kuml.asciidoc

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Internal glue: evaluate a kUML script string and render to SVG/PNG bytes.
 *
 * Spiegelt `MarkdownRenderPipeline` aus `kuml-docs/kuml-markdown` — gleiche
 * Layout-/Theme-Auswahl, gleiche API.
 *
 * **Coverage** (V3.2.19): [ExtractedDiagram.Uml] (all `classDiagram`/`stateDiagram`/…
 * family diagrams) and [ExtractedDiagram.Blueprint] (Service Blueprint / Journey Map —
 * no ELK layout, direct geometry-driven renderer). C4, SysML 2 and BPMN scripts are
 * **not yet supported** here — [evaluate] throws a clear [ScriptEvaluationException]
 * naming the unsupported kind rather than silently mis-rendering. `kuml-cli`'s
 * `RenderPipeline` is the reference for adding those (ELK layout bridge +
 * kind-specific `KumlSvgRenderer.toSvg` overload per kind).
 */
internal object AsciidocRenderPipeline {
    private val layoutEngine = ElkLayoutEngine()
    private val theme = PlainTheme()

    internal fun evaluate(
        source: String,
        virtualName: String,
    ): ExtractedDiagram {
        val evalResult = KumlScriptHost.eval(source, virtualName)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val message = errors.joinToString("\n") { it.message }
            throw ScriptEvaluationException("Script evaluation failed in '$virtualName':\n$message")
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException("Script '$virtualName' produced no result")
        val extracted = DiagramExtractor.extractAny(success.value.returnValue, File(virtualName))
        return when (extracted) {
            is ExtractedDiagram.Uml, is ExtractedDiagram.Blueprint -> extracted
            is ExtractedDiagram.C4 ->
                throw ScriptEvaluationException(
                    "Script '$virtualName' produced a C4 diagram. C4 embedding in AsciiDoc is not yet " +
                        "supported by kuml-asciidoc (only UML and Blueprint diagrams render here) — " +
                        "see AsciidocRenderPipeline KDoc.",
                )
            is ExtractedDiagram.Sysml2 ->
                throw ScriptEvaluationException(
                    "Script '$virtualName' produced a SysML 2 diagram. SysML 2 embedding in AsciiDoc " +
                        "is not yet supported by kuml-asciidoc (only UML and Blueprint diagrams render " +
                        "here) — see AsciidocRenderPipeline KDoc.",
                )
            is ExtractedDiagram.Bpmn ->
                throw ScriptEvaluationException(
                    "Script '$virtualName' produced a BPMN diagram. BPMN embedding in AsciiDoc is not " +
                        "yet supported by kuml-asciidoc (only UML and Blueprint diagrams render here) — " +
                        "see AsciidocRenderPipeline KDoc.",
                )
        }
    }

    internal fun renderSvg(extracted: ExtractedDiagram): String =
        when (extracted) {
            is ExtractedDiagram.Uml -> {
                val layoutGraph = UmlLayoutBridge.toLayoutGraph(extracted.diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(extracted.diagram, layoutResult, theme)
            }
            is ExtractedDiagram.Blueprint -> KumlSvgRenderer.toSvg(extracted.model, extracted.diagram, theme)
            else -> unsupported(extracted)
        }

    internal fun renderPng(
        extracted: ExtractedDiagram,
        widthPx: Int,
    ): ByteArray =
        when (extracted) {
            is ExtractedDiagram.Uml -> {
                val layoutGraph = UmlLayoutBridge.toLayoutGraph(extracted.diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlPngRenderer.toPng(extracted.diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx))
            }
            is ExtractedDiagram.Blueprint -> {
                val svg = KumlSvgRenderer.toSvg(extracted.model, extracted.diagram, theme)
                KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = widthPx))
            }
            else -> unsupported(extracted)
        }

    /** Display name used for `image::…[alt]` alt text and similar. */
    internal fun diagramName(extracted: ExtractedDiagram): String =
        when (extracted) {
            is ExtractedDiagram.Uml -> extracted.diagram.name
            is ExtractedDiagram.Blueprint -> extracted.diagram.name
            is ExtractedDiagram.C4 -> extracted.diagram.name
            is ExtractedDiagram.Sysml2 -> extracted.diagram.name
            is ExtractedDiagram.Bpmn -> extracted.diagram.name
        }

    private fun unsupported(extracted: ExtractedDiagram): Nothing =
        throw ScriptEvaluationException(
            "Unsupported diagram kind for kuml-asciidoc rendering: ${extracted::class.simpleName}",
        )
}
