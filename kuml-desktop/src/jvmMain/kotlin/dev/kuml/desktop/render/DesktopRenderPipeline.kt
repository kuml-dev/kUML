package dev.kuml.desktop.render

import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.renderer.theme.core.ThemeRegistry
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.UcDiagram
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

internal object DesktopRenderPipeline {

    fun render(script: String, themeName: String): DesktopRenderResult {
        DesktopEngineInit.ensure()
        return try {
            val evalResult = KumlScriptHost.eval(script)
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                val msg = errors.joinToString("\n") { it.message }
                return DesktopRenderResult.Error(msg.ifBlank { "Script-Auswertung fehlgeschlagen" })
            }
            val success = evalResult as? ResultWithDiagnostics.Success
                ?: return DesktopRenderResult.Error("Kein Ergebnis aus dem Script")

            val extracted = DiagramExtractor.extractAny(
                success.value.returnValue,
                File("inline.kuml.kts"),
            )

            val theme = ThemeRegistry.get(themeName)
                ?: ThemeRegistry.get("plain")
                ?: return DesktopRenderResult.Error("Theme '$themeName' nicht gefunden")

            val elkEngine = LayoutEngineRegistry.get("elk.layered")
                ?: return DesktopRenderResult.Error("ELK-Layout-Engine nicht verfügbar")

            val svg = when (extracted) {
                is ExtractedDiagram.Uml -> {
                    val graph = UmlLayoutBridge.toLayoutGraph(extracted.diagram)
                    val layout = elkEngine.layout(graph, LayoutHints.DEFAULT)
                    KumlSvgRenderer.toSvg(extracted.diagram, layout, theme)
                }
                is ExtractedDiagram.C4 -> {
                    val sizeProvider = C4ContentSizeProvider(extracted.model)
                    val graph = C4LayoutBridge.toLayoutGraph(extracted.diagram, extracted.model, sizeProvider)
                    val layout = elkEngine.layout(graph, LayoutHints.DEFAULT)
                    KumlSvgRenderer.toSvg(extracted.diagram, extracted.model, layout, theme)
                }
                is ExtractedDiagram.Sysml2 -> {
                    val model = extracted.model
                    val paddingOpts = SvgRenderOptions(paddingPx = 64f)
                    when (val diagram = extracted.diagram) {
                        is BdDiagram ->
                            KumlSvgRenderer.toSvg(
                                model,
                                diagram,
                                elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                theme,
                            )
                        is IbdDiagram ->
                            KumlSvgRenderer.toSvg(
                                model,
                                diagram,
                                elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                theme,
                            )
                        is UcDiagram ->
                            KumlSvgRenderer.toSvg(
                                model,
                                diagram,
                                elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                theme,
                            )
                        is ReqDiagram ->
                            KumlSvgRenderer.toSvg(
                                model,
                                diagram,
                                elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                theme,
                            )
                        is StmDiagram ->
                            KumlSvgRenderer.toSvg(
                                model,
                                diagram,
                                elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                theme,
                                paddingOpts,
                            )
                        is ActDiagram ->
                            KumlSvgRenderer.toSvg(
                                model,
                                diagram,
                                elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                theme,
                                paddingOpts,
                            )
                        is SeqDiagram ->
                            KumlSvgRenderer.toSvg(
                                model,
                                diagram,
                                elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                theme,
                            )
                        is ParDiagram ->
                            KumlSvgRenderer.toSvg(
                                model,
                                diagram,
                                elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                theme,
                            )
                    }
                }
                is ExtractedDiagram.Bpmn -> {
                    when (val diagram = extracted.diagram) {
                        is ProcessDiagram -> {
                            val process =
                                extracted.model.processes.firstOrNull { it.id == diagram.processId }
                            val elements =
                                if (process != null) {
                                    process.flowNodes + process.sequenceFlows + process.dataObjects
                                } else {
                                    emptyList()
                                }
                            val kumlDiagram =
                                KumlDiagram(
                                    name = diagram.name,
                                    type = DiagramType.BPMN_PROCESS,
                                    elements = elements,
                                )
                            val layout =
                                elkEngine.layout(
                                    BpmnLayoutBridge.toLayoutGraph(extracted.model, diagram),
                                    LayoutHints.DEFAULT,
                                )
                            KumlSvgRenderer.toSvg(kumlDiagram, layout, theme)
                        }
                        is CollaborationDiagram -> {
                            val layout =
                                elkEngine.layout(
                                    BpmnLayoutBridge.toLayoutGraph(extracted.model, diagram),
                                    LayoutHints.DEFAULT,
                                )
                            KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme)
                        }
                    }
                }
            }
            DesktopRenderResult.Svg(svg)

        } catch (e: ScriptEvaluationException) {
            DesktopRenderResult.Error(e.message ?: "Script-Fehler")
        } catch (e: Exception) {
            DesktopRenderResult.Error(e.message ?: "Unerwarteter Fehler")
        }
    }
}
