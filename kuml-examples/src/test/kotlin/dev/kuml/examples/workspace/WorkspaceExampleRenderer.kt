package dev.kuml.examples.workspace

import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.UmlContentSizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import dev.kuml.renderer.theme.core.ThemeRegistry
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Render-harness result for [WorkspaceExampleRenderer.render] — `svg` is non-null
 * on success, `error` is non-null on failure. Mirrors
 * `dev.kuml.vaultexamples.RenderResult` (kuml-vault-examples-tests), trimmed to
 * a single field this module actually needs.
 */
public data class WorkspaceRenderResult(
    val svg: String?,
    val error: String?,
)

/**
 * Trimmed render harness for the FT-3 sample-association-charter demo workspace
 * (V3.6.3) — covers only the two [ExtractedDiagram] variants this asset uses:
 * [ExtractedDiagram.Uml] (class + state-machine diagrams) and
 * [ExtractedDiagram.Bpmn] (process diagrams).
 *
 * Modelled on `dev.kuml.vaultexamples.VaultExampleRenderer` (kuml-vault-examples-tests)
 * — see that file for the full dispatch across every `ExtractedDiagram` variant
 * (C4, SysML 2, Blueprint, ERM) that this trimmed copy intentionally omits, since
 * the sample-association-charter workspace only contains UML and BPMN diagrams.
 *
 * No disk writes — renders purely in memory, so there is no file-write path to
 * reason about for this test-only helper.
 */
public object WorkspaceExampleRenderer {
    public fun render(
        script: String,
        themeName: String = "elegant",
    ): WorkspaceRenderResult {
        ensureInit()
        return try {
            val evalResult = KumlScriptHost.eval(code = script)
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                val msg = errors.joinToString("\n") { it.message }
                return WorkspaceRenderResult(svg = null, error = msg.ifBlank { "Script evaluation failed" })
            }
            val success =
                evalResult as? ResultWithDiagnostics.Success
                    ?: return WorkspaceRenderResult(svg = null, error = "No result from script")

            val extracted =
                DiagramExtractor.extractAny(
                    returnValue = success.value.returnValue,
                    input = File("inline.kuml.kts"),
                )

            val theme =
                ThemeRegistry.get(themeName)
                    ?: ThemeRegistry.get("plain")
                    ?: return WorkspaceRenderResult(svg = null, error = "Theme not found")

            val elkEngine =
                LayoutEngineRegistry.get("elk.layered")
                    ?: return WorkspaceRenderResult(svg = null, error = "ELK layout engine not available")

            when (extracted) {
                is ExtractedDiagram.Uml -> {
                    val sizeProvider = UmlContentSizeProvider(diagram = extracted.diagram)
                    val graph = UmlLayoutBridge.toLayoutGraph(diagram = extracted.diagram, sizeProvider = sizeProvider)
                    val layout = elkEngine.layout(graph = graph, hints = LayoutHints.DEFAULT)
                    val svg = KumlSvgRenderer.toSvg(diagram = extracted.diagram, layoutResult = layout, theme = theme)
                    WorkspaceRenderResult(svg = svg, error = null)
                }

                is ExtractedDiagram.Bpmn -> {
                    val svg =
                        when (val diagram = extracted.diagram) {
                            is ProcessDiagram -> {
                                val process =
                                    extracted.model.processes.firstOrNull { it.id == diagram.processId }
                                val elements = process?.renderableElements() ?: emptyList()
                                val kumlDiagram =
                                    KumlDiagram(
                                        name = diagram.name,
                                        type = DiagramType.BPMN_PROCESS,
                                        elements = elements,
                                    )
                                val layout =
                                    elkEngine.layout(
                                        graph = BpmnLayoutBridge.toLayoutGraph(model = extracted.model, diagram = diagram),
                                        hints = LayoutHints.DEFAULT,
                                    )
                                KumlSvgRenderer.toSvg(diagram = kumlDiagram, layoutResult = layout, theme = theme)
                            }
                            else ->
                                return WorkspaceRenderResult(
                                    svg = null,
                                    error = "Unsupported BPMN diagram kind for this trimmed renderer: ${diagram::class.simpleName}",
                                )
                        }
                    WorkspaceRenderResult(svg = svg, error = null)
                }

                else ->
                    WorkspaceRenderResult(
                        svg = null,
                        error = "Unsupported diagram kind for this trimmed renderer: ${extracted::class.simpleName}",
                    )
            }
        } catch (e: Exception) {
            WorkspaceRenderResult(svg = null, error = "Render exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Volatile private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            if (LayoutEngineRegistry.ids().isEmpty()) {
                LayoutEngineRegistry.register(GridLayoutEngineProvider())
                LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            }
            if (ThemeRegistry.names().isEmpty()) {
                ThemeRegistry.loadFromClasspath()
            }
            initialized = true
        }
    }
}
