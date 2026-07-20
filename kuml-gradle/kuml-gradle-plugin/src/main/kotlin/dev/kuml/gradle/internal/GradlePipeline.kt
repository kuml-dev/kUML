package dev.kuml.gradle.internal

import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScript
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnContentSizeProvider
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.layout.bridge.bpmn.ChoreographyGridLayout
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
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
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.createCompilationConfigurationFromTemplate
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.host.withDefaultsFrom
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/**
 * Geteilter Render-/Extraction-Glue zwischen den Gradle-Tasks.
 *
 * Spiegelt die `RenderPipeline` aus `kuml-cli` 1:1, ohne deren `internal`-Visibility
 * zu durchbrechen. Identisch zu `MarkdownRenderPipeline` / `AsciidocRenderPipeline`.
 */
internal object GradlePipeline {
    private val layoutEngine = ElkLayoutEngine()

    // V3.0.x — see RenderPipeline.kt (CLI) for the full rationale: UML sequence
    // diagrams are the one diagram type where declaration order is semantically
    // meaningful, so pin it via LayoutHints.preserveNodeOrder.
    private fun umlHintsFor(diagram: KumlDiagram): LayoutHints =
        LayoutHints.DEFAULT.copy(preserveNodeOrder = diagram.type == DiagramType.SEQUENCE)

    /**
     * Plugin-eigener Scripting-Host für Gradle-Worker-Klassen­hierarchien.
     *
     * **Warum kein `KumlScriptHost`?**
     * `createJvmCompilationConfigurationFromTemplate<T>()` ruft intern
     * `createCompilationConfigurationFromTemplate(KotlinType(T::class), …,
     * contextClass = ScriptCompilationConfiguration::class)`. Der hardcodierte
     * `contextClass` bestimmt über `contextClass.java.classLoader`, welcher
     * Classloader die Template-Klasse `KumlScript` findet.
     *
     * In Gradle-Workern lädt der **Parent-Classloader** `kotlin-scripting-common`
     * (und damit auch `ScriptCompilationConfiguration`), während der
     * **Plugin-Child-Classloader** `kuml-core-script` (und damit `KumlScript`)
     * lädt. `JvmGetScriptingClass.invoke` läuft daher den Parent-Chain ab und
     * findet `KumlScript` nicht — `ClassNotFoundException: KumlScript`.
     *
     * Workaround: wir umgehen die `<reified T>`-Variante und rufen direkt
     * [createCompilationConfigurationFromTemplate] mit `contextClass = KumlScript::class`
     * auf. Damit ist der Plugin-Classloader der "Wurzel" des Lookups und alles
     * funktioniert auch in einem isolierten Gradle-Worker.
     */
    private val pluginClassLoader: ClassLoader = KumlScript::class.java.classLoader

    private val hostConfiguration =
        ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            jvm {
                baseClassLoader(pluginClassLoader)
            }
        }

    private val scriptingHost = BasicJvmScriptingHost(baseHostConfiguration = hostConfiguration)

    private val compilationConfig: ScriptCompilationConfiguration by lazy {
        createCompilationConfigurationFromTemplate(
            baseClassType = KotlinType(KumlScript::class),
            baseHostConfiguration = hostConfiguration.withDefaultsFrom(defaultJvmScriptingHostConfiguration),
            contextClass = KumlScript::class,
        )
    }

    /** Evaluiere ein Script und liefere die ExtractedDiagram-Variante (UML | C4). */
    internal fun evaluate(file: File): ExtractedDiagram =
        withPluginClassLoader {
            val result =
                scriptingHost.eval(
                    script = file.toScriptSource(),
                    compilationConfiguration = compilationConfig,
                    evaluationConfiguration = null,
                )
            val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || result is ResultWithDiagnostics.Failure) {
                throw ScriptEvaluationException(
                    "Script evaluation failed for '${file.path}':\n" +
                        errors.joinToString("\n") { it.message },
                )
            }
            val success =
                result as? ResultWithDiagnostics.Success
                    ?: throw ScriptEvaluationException("Script '${file.name}' produced no result")
            DiagramExtractor.extractAny(success.value.returnValue, file)
        }

    /**
     * Setzt zusätzlich noch den Thread-Context-Classloader, weil
     * `dependenciesFromCurrentContext(wholeClasspath = true)` in
     * `KumlScriptCompilationConfiguration` zur Laufzeit als Fallback ebenfalls
     * den Context-Classloader anzapft, wenn der host config das tut.
     */
    private inline fun <T> withPluginClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        return try {
            thread.contextClassLoader = pluginClassLoader
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    /** Resolve a theme by name via the ServiceLoader-driven registry. */
    internal fun resolveTheme(name: String): KumlTheme {
        if (ThemeRegistry.names().isEmpty()) ThemeRegistry.loadFromClasspath()
        return ThemeRegistry.get(name)
            ?: error("Unknown theme '$name'. Registered themes: ${ThemeRegistry.names()}")
    }

    /**
     * Render an extracted diagram to SVG (string).
     *
     * @param watermark "Powered by kUML" opt-in visible watermark; default `false`.
     *   Blueprint diagrams are the one exception — see the KDoc on the
     *   CLI's `RenderPipeline.renderBlueprint` for why that renderer has no
     *   [SvgRenderOptions] plumbing at all.
     */
    internal fun renderSvg(
        extracted: ExtractedDiagram,
        theme: KumlTheme,
        watermark: Boolean = false,
    ): String {
        val svgOptions = SvgRenderOptions(watermark = watermark)
        return when (extracted) {
            is ExtractedDiagram.Uml -> {
                val layout = layoutEngine.layout(UmlLayoutBridge.toLayoutGraph(extracted.diagram), umlHintsFor(extracted.diagram))
                KumlSvgRenderer.toSvg(extracted.diagram, layout, theme, svgOptions)
            }
            is ExtractedDiagram.C4 -> {
                val sizeProvider = C4ContentSizeProvider(extracted.model)
                val layout =
                    layoutEngine.layout(
                        C4LayoutBridge.toLayoutGraph(extracted.diagram, extracted.model, sizeProvider),
                        LayoutHints.DEFAULT,
                    )
                KumlSvgRenderer.toSvg(extracted.diagram, extracted.model, layout, theme, svgOptions)
            }
            is ExtractedDiagram.Sysml2 ->
                when (val diagram = extracted.diagram) {
                    is BdDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                Sysml2LayoutBridge.toLayoutGraph(extracted.model, diagram),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                    is IbdDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                Sysml2LayoutBridge.toLayoutGraph(extracted.model, diagram),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                    is UcDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                Sysml2LayoutBridge.toLayoutGraph(extracted.model, diagram),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                    is ReqDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                Sysml2LayoutBridge.toLayoutGraph(extracted.model, diagram),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                    is StmDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                Sysml2LayoutBridge.toLayoutGraph(extracted.model, diagram),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                    is ActDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                Sysml2LayoutBridge.toLayoutGraph(extracted.model, diagram),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                    is SeqDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                Sysml2LayoutBridge.toLayoutGraph(extracted.model, diagram),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                    is ParDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                Sysml2LayoutBridge.toLayoutGraph(extracted.model, diagram),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                }
            is ExtractedDiagram.Bpmn -> {
                when (val diagram = extracted.diagram) {
                    is ProcessDiagram -> {
                        val process = extracted.model.processes.firstOrNull { it.id == diagram.processId }
                        val elements: List<dev.kuml.core.model.KumlElement> =
                            if (process != null) process.flowNodes + process.sequenceFlows + process.dataObjects else emptyList()
                        val kumlDiagram = KumlDiagram(name = diagram.name, type = DiagramType.BPMN_PROCESS, elements = elements)
                        val layout =
                            layoutEngine.layout(
                                BpmnLayoutBridge.toLayoutGraph(extracted.model, diagram, BpmnContentSizeProvider(extracted.model)),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(kumlDiagram, layout, theme, svgOptions)
                    }
                    is CollaborationDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                BpmnLayoutBridge.toLayoutGraph(extracted.model, diagram, BpmnContentSizeProvider(extracted.model)),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                    is ChoreographyDiagram -> {
                        // V3.2.2 — Choreography bypasses ELK entirely: deterministic custom grid layout.
                        val layout = ChoreographyGridLayout.layout(extracted.model, diagram)
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                    is ConversationDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                BpmnLayoutBridge.toLayoutGraph(extracted.model, diagram, BpmnContentSizeProvider(extracted.model)),
                                LayoutHints.DEFAULT,
                            )
                        KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                    }
                }
            }
            // V3.1.24: Blueprint / Journey-Map — no ELK, deterministic grid. No
            // SvgRenderOptions plumbing exists for this renderer (see KDoc above),
            // so `watermark` has no effect here; the always-on attribution comment
            // is still emitted (BlueprintGridSvg calls SvgDocument.attributionComment()).
            is ExtractedDiagram.Blueprint -> KumlSvgRenderer.toSvg(extracted.model, extracted.diagram)
            // V3.4.1: ERM rendering is out of scope — planned for V3.4.2.
            is ExtractedDiagram.Erm ->
                throw ScriptEvaluationException(
                    "ERM-Rendering wird noch nicht unterstützt — geplant für kUML V3.4.2. " +
                        "V3.4.1 unterstützt für ERM-Skripte nur `kuml validate`.",
                )
        }
    }

    /**
     * Render an extracted diagram to PNG bytes.
     *
     * @param watermark "Powered by kUML" opt-in visible watermark; default `false`.
     *   See [renderSvg]'s KDoc for the Blueprint exception.
     */
    internal fun renderPng(
        extracted: ExtractedDiagram,
        theme: KumlTheme,
        widthPx: Int,
        watermark: Boolean = false,
    ): ByteArray {
        val options = PngRenderOptions(widthPx = widthPx)
        val svgOptions = SvgRenderOptions(watermark = watermark)
        return when (extracted) {
            is ExtractedDiagram.Uml -> {
                val layout = layoutEngine.layout(UmlLayoutBridge.toLayoutGraph(extracted.diagram), umlHintsFor(extracted.diagram))
                KumlPngRenderer.toPng(extracted.diagram, layout, theme, options, svgOptions)
            }
            is ExtractedDiagram.C4 -> {
                val sizeProvider = C4ContentSizeProvider(extracted.model)
                val layout =
                    layoutEngine.layout(
                        C4LayoutBridge.toLayoutGraph(extracted.diagram, extracted.model, sizeProvider),
                        LayoutHints.DEFAULT,
                    )
                KumlPngRenderer.toPng(extracted.diagram, extracted.model, layout, theme, options, svgOptions)
            }
            // V2.0.4 (BDD) / V2.0.6 (IBD): PNG-Export für SysML 2 ist V2.x.
            // Spiegelt das CLI-Verhalten in `RenderPipeline.renderSysml2`.
            is ExtractedDiagram.Sysml2 -> throw ScriptEvaluationException(
                "PNG-Export für SysML 2 (${extracted.diagram::class.simpleName}) ist V2.x — " +
                    "bis dahin bitte SVG oder LaTeX nutzen.",
            )
            is ExtractedDiagram.Bpmn -> {
                when (val diagram = extracted.diagram) {
                    is ProcessDiagram -> {
                        val process = extracted.model.processes.firstOrNull { it.id == diagram.processId }
                        val elements: List<dev.kuml.core.model.KumlElement> =
                            if (process != null) process.flowNodes + process.sequenceFlows + process.dataObjects else emptyList()
                        val kumlDiagram = KumlDiagram(name = diagram.name, type = DiagramType.BPMN_PROCESS, elements = elements)
                        val layout =
                            layoutEngine.layout(
                                BpmnLayoutBridge.toLayoutGraph(extracted.model, diagram, BpmnContentSizeProvider(extracted.model)),
                                LayoutHints.DEFAULT,
                            )
                        KumlPngRenderer.toPng(kumlDiagram, layout, theme, options, svgOptions)
                    }
                    is CollaborationDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                BpmnLayoutBridge.toLayoutGraph(extracted.model, diagram, BpmnContentSizeProvider(extracted.model)),
                                LayoutHints.DEFAULT,
                            )
                        val svg = KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                        KumlPngRenderer.toPng(svg, options)
                    }
                    is ChoreographyDiagram -> {
                        // V3.2.2 — Choreography bypasses ELK entirely: deterministic custom grid layout.
                        val layout = ChoreographyGridLayout.layout(extracted.model, diagram)
                        val svg = KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                        KumlPngRenderer.toPng(svg, options)
                    }
                    is ConversationDiagram -> {
                        val layout =
                            layoutEngine.layout(
                                BpmnLayoutBridge.toLayoutGraph(extracted.model, diagram, BpmnContentSizeProvider(extracted.model)),
                                LayoutHints.DEFAULT,
                            )
                        val svg = KumlSvgRenderer.toSvg(extracted.model, diagram, layout, theme, svgOptions)
                        KumlPngRenderer.toPng(svg, options)
                    }
                }
            }
            // V3.1.24: Blueprint / Journey-Map — no ELK, deterministic grid. No
            // SvgRenderOptions plumbing exists for this renderer, so `watermark`
            // has no effect here (same exception documented on [renderSvg]).
            is ExtractedDiagram.Blueprint -> {
                val svg = KumlSvgRenderer.toSvg(extracted.model, extracted.diagram)
                KumlPngRenderer.toPng(svg, options)
            }
            // V3.4.1: ERM rendering is out of scope — planned for V3.4.2.
            is ExtractedDiagram.Erm ->
                throw ScriptEvaluationException(
                    "ERM-Rendering wird noch nicht unterstützt — geplant für kUML V3.4.2. " +
                        "V3.4.1 unterstützt für ERM-Skripte nur `kuml validate`.",
                )
        }
    }

    /** Get the diagram name from an extracted variant. */
    internal fun diagramName(extracted: ExtractedDiagram): String =
        when (extracted) {
            is ExtractedDiagram.Uml -> extracted.diagram.name
            is ExtractedDiagram.C4 -> extracted.diagram.name
            is ExtractedDiagram.Sysml2 -> extracted.diagram.name
            is ExtractedDiagram.Bpmn -> extracted.diagram.name
            is ExtractedDiagram.Blueprint -> extracted.diagram.name
            is ExtractedDiagram.Erm -> extracted.diagram.name
        }

    /** Default (fallback) theme — used when the user's pick fails to resolve. */
    internal fun defaultTheme(): KumlTheme = PlainTheme()
}
