package dev.kuml.vaultexamples

import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.io.svg.bpmn.smil.AnimatedBpmnRenderResult
import dev.kuml.io.svg.bpmn.smil.BpmnAnimationContext
import dev.kuml.io.svg.bpmn.smil.BpmnSmilRenderer
import dev.kuml.io.svg.stm.smil.AnimatedStmRenderResult
import dev.kuml.io.svg.stm.smil.StmAnimationContext
import dev.kuml.io.svg.stm.smil.StmSmilRenderer
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.UmlContentSizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.render.smil.SmilEmitter
import dev.kuml.render.smil.SmilTimeline
import dev.kuml.render.smil.StaticSnapshotMode
import dev.kuml.runtime.KumlRuntimeJson
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlStateMachine
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Test-only helper that renders animated (SMIL) vault examples.
 *
 * Reuses [VaultExampleRenderer]'s init/ELK/theme plumbing but returns the animated SVG
 * from [BpmnSmilRenderer] or [StmSmilRenderer] instead of the static [dev.kuml.io.svg.KumlSvgRenderer].
 *
 * V3.1.32 — SMIL vault examples + vault-examples SMIL tests
 */
internal object AnimatedExampleRenderer {
    private val paddingOpts = SvgRenderOptions(paddingPx = 64f)

    /**
     * Renders an animated BPMN vault example.
     *
     * @param script The kUML script text from the `.md` file's ` ```kuml ` block.
     * @param traceJson A `kuml.trace.v1` JSON string. Parsed via [KumlRuntimeJson].
     * @param context Animation tuning. Defaults to [BpmnAnimationContext.DEFAULT].
     * @return [AnimatedBpmnRenderResult] with the SVG and animation flag.
     */
    fun renderBpmn(
        script: String,
        traceJson: String,
        context: BpmnAnimationContext = BpmnAnimationContext.DEFAULT,
    ): AnimatedBpmnRenderResult {
        VaultExampleRenderer.init()

        val elkEngine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK-Layout-Engine nicht verfügbar")

        val traceFile = KumlRuntimeJson.decodeFromString(TraceFile.serializer(), traceJson)

        val evalResult = KumlScriptHost.eval(script)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        check(errors.isEmpty() && evalResult !is ResultWithDiagnostics.Failure) {
            errors.joinToString("\n") { it.message }.ifBlank { "Script-Auswertung fehlgeschlagen" }
        }
        val success = evalResult as ResultWithDiagnostics.Success

        val extracted =
            DiagramExtractor.extractAny(success.value.returnValue, File("inline.kuml.kts"))
        check(extracted is ExtractedDiagram.Bpmn) {
            "Erwartet ExtractedDiagram.Bpmn, erhalten: ${extracted::class.simpleName}"
        }

        val diagram = extracted.diagram
        check(diagram is ProcessDiagram) {
            "Erwartet ProcessDiagram für SMIL, erhalten: ${diagram::class.simpleName}"
        }

        val process = extracted.model.processes.firstOrNull { it.id == diagram.processId }
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

        return BpmnSmilRenderer.render(
            diagram = kumlDiagram,
            layoutResult = layout,
            options = SvgRenderOptions.DEFAULT,
            trace = traceFile,
            context = context,
        )
    }

    /**
     * Renders an animated UML State Machine (STM) vault example.
     *
     * The script must use the `stateDiagram { }` DSL (not `sysml2Model`), because
     * [StmSmilRenderer] requires a [UmlStateMachine] which only the UML stateDiagram path
     * yields as an element inside [KumlDiagram.elements].
     *
     * @param script The kUML script text from the `.md` file's ` ```kuml ` block.
     * @param traceJson A `kuml.trace.v1` JSON string. Parsed via [KumlRuntimeJson].
     * @param context Animation tuning. Defaults to [StmAnimationContext.DEFAULT].
     * @return [AnimatedStmRenderResult] with the SVG and animation flag.
     */
    fun renderStm(
        script: String,
        traceJson: String,
        context: StmAnimationContext = StmAnimationContext.DEFAULT,
    ): AnimatedStmRenderResult {
        VaultExampleRenderer.init()

        val elkEngine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK-Layout-Engine nicht verfügbar")

        val traceFile = KumlRuntimeJson.decodeFromString(TraceFile.serializer(), traceJson)

        val evalResult = KumlScriptHost.eval(script)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        check(errors.isEmpty() && evalResult !is ResultWithDiagnostics.Failure) {
            errors.joinToString("\n") { it.message }.ifBlank { "Script-Auswertung fehlgeschlagen" }
        }
        val success = evalResult as ResultWithDiagnostics.Success

        val extracted =
            DiagramExtractor.extractAny(success.value.returnValue, File("inline.kuml.kts"))
        check(extracted is ExtractedDiagram.Uml) {
            "Erwartet ExtractedDiagram.Uml, erhalten: ${extracted::class.simpleName}"
        }

        val stateMachine =
            extracted.diagram.elements
                .filterIsInstance<UmlStateMachine>()
                .firstOrNull()
                ?: error("Kein UmlStateMachine in KumlDiagram.elements gefunden")

        val sizeProvider = UmlContentSizeProvider(extracted.diagram)
        val graph = UmlLayoutBridge.toLayoutGraph(extracted.diagram, sizeProvider)
        val layout = elkEngine.layout(graph, LayoutHints.DEFAULT)

        return StmSmilRenderer.render(
            diagram = extracted.diagram,
            stateMachine = stateMachine,
            layoutResult = layout,
            options = paddingOpts,
            trace = traceFile,
            context = context,
        )
    }

    /**
     * Strips all SMIL animation elements from [animatedSvg] via [SmilEmitter].
     *
     * The result must contain none of `<animate`, `<animateMotion`, `<animateTransform`, `<set `.
     *
     * @param animatedSvg The animated SVG string produced by [renderBpmn] or [renderStm].
     * @return Static SVG string with all SMIL elements removed.
     */
    fun stripSmil(animatedSvg: String): String = SmilEmitter().inject(animatedSvg, SmilTimeline(emptyList()), StaticSnapshotMode.STRIPPED)
}
