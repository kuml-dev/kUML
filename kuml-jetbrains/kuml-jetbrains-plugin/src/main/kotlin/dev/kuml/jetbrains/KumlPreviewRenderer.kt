package dev.kuml.jetbrains

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Thin rendering facade used by [KumlPreviewPanel].
 *
 * Decoupled from the panel so unit tests can test debounce + status logic
 * independently of the full kUML rendering pipeline. The heavy coupling to
 * [KumlScriptHost] lives here and can be swapped or mocked at the call site.
 *
 * V2.0.30 wires the full render pipeline:
 *  KumlScriptHost.eval → DiagramExtractor.extractAny → layout → KumlSvgRenderer.toSvg
 *
 * The SVG and layout modules are loaded via the plugin classpath at runtime
 * inside the running IntelliJ process. If those classes are absent (e.g. in
 * a unit-test environment without the full plugin classpath), all reflective
 * calls fail gracefully and `render` returns `null` — the panel shows
 * "No diagram" without crashing.
 */
internal object KumlPreviewRenderer {
    /**
     * Render the given `.kuml.kts` [scriptText] to an SVG string.
     *
     * Returns `null` if the script has no renderable diagram, compilation fails,
     * or required renderer classes are not on the classpath.
     *
     * @param scriptText Full text of the `.kuml.kts` file.
     * @param scriptName A name / path used for diagnostics.
     */
    fun render(
        scriptText: String,
        scriptName: String,
    ): String? =
        try {
            renderInternal(scriptText, scriptName)
        } catch (_: Exception) {
            null
        }

    private fun renderInternal(
        scriptText: String,
        scriptName: String,
    ): String? {
        // 1. Compile + evaluate the script.
        val evalResult = KumlScriptHost.eval(scriptText, scriptName)
        val errors = evalResult.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) return null

        val success = evalResult as? ResultWithDiagnostics.Success ?: return null

        // 2. Extract diagram — UML, C4, or SysML 2.
        //    extractAny requires a File for error messages; create a synthetic placeholder.
        val syntheticFile = java.io.File(scriptName)
        val extracted: ExtractedDiagram =
            try {
                DiagramExtractor.extractAny(success.value.returnValue, syntheticFile)
            } catch (_: Throwable) {
                return null
            }

        // 3. Layout + SVG emission — loaded via the plugin runtime classpath.
        // We use reflection so this file compiles even if kuml-io-svg /
        // kuml-layout are not direct compile-time dependencies of the plugin
        // module. Inside the running IDE all kUML jars are on the classloader.
        return renderToSvgReflective(extracted)
    }

    /**
     * Reflectively invoke the layout + SVG pipeline.
     *
     * Using reflection keeps the plugin module's compile-time dependency list
     * minimal (only kuml-core-* and kuml-metamodel-*), while still calling the
     * real renderer when the full classpath is present at runtime.
     *
     * Returns `null` if any required class / method is missing (headless test
     * environments, incomplete classpath).
     */
    private fun renderToSvgReflective(extracted: ExtractedDiagram): String? =
        try {
            val cl = KumlPreviewRenderer::class.java.classLoader

            // Theme
            val themeRegistryClass = cl.loadClass("dev.kuml.renderer.theme.core.ThemeRegistry")
            val namesMethod = themeRegistryClass.getMethod("names")
            val loadMethod = themeRegistryClass.getMethod("loadFromClasspath")
            @Suppress("UNCHECKED_CAST")
            if ((namesMethod.invoke(null) as Collection<*>).isEmpty()) {
                loadMethod.invoke(null)
            }
            val getThemeMethod = themeRegistryClass.getMethod("get", String::class.java)
            val theme = getThemeMethod.invoke(null, "plain") ?: return null

            // Layout engine registry
            val registryClass = cl.loadClass("dev.kuml.layout.LayoutEngineRegistry")
            val regIdsMethod = registryClass.getMethod("ids")
            @Suppress("UNCHECKED_CAST")
            if ((regIdsMethod.invoke(null) as Collection<*>).isEmpty()) {
                val gridProviderClass = cl.loadClass("dev.kuml.layout.grid.GridLayoutEngineProvider")
                val elkProviderClass = cl.loadClass("dev.kuml.layout.elk.ElkLayoutEngineProvider")
                val registerMethod = registryClass.getMethod("register", cl.loadClass("dev.kuml.layout.KumlLayoutEngineProvider"))
                registerMethod.invoke(null, gridProviderClass.getDeclaredConstructor().newInstance())
                registerMethod.invoke(null, elkProviderClass.getDeclaredConstructor().newInstance())
            }

            val layoutHintsClass = cl.loadClass("dev.kuml.layout.LayoutHints")
            val defaultHints = layoutHintsClass.getField("DEFAULT").get(null)

            when (extracted) {
                is ExtractedDiagram.Uml -> {
                    val diagram = extracted.diagram
                    val bridgeClass = cl.loadClass("dev.kuml.layout.bridge.UmlLayoutBridge")
                    val toGraphMethod = bridgeClass.getMethod("toLayoutGraph", diagram::class.java)
                    val layoutGraph = toGraphMethod.invoke(null, diagram)

                    val engineId = cl.loadClass("dev.kuml.layout.LayoutEngineId")
                    val gridId =
                        engineId
                            .getDeclaredConstructor(String::class.java)
                            .newInstance("kuml.grid")
                    val diagramKindClass = cl.loadClass("dev.kuml.layout.DiagramKind")
                    val pickForMethod =
                        registryClass.getMethod("pickFor", diagramKindClass, engineId)
                    val genericKind = diagramKindClass.getField("Generic").get(null)
                    val engine = pickForMethod.invoke(null, genericKind, gridId) ?: return null

                    val layoutEngineInterface = cl.loadClass("dev.kuml.layout.KumlLayoutEngine")
                    val layoutGraphClass = cl.loadClass("dev.kuml.layout.LayoutGraph")
                    val layoutMethod =
                        layoutEngineInterface.getMethod("layout", layoutGraphClass, layoutHintsClass)
                    val layoutResult = layoutMethod.invoke(engine, layoutGraph, defaultHints)

                    val svgRendererClass = cl.loadClass("dev.kuml.io.svg.KumlSvgRenderer")
                    val svgRenderOptionsClass = cl.loadClass("dev.kuml.io.svg.SvgRenderOptions")
                    val optionsDefault = svgRenderOptionsClass.getField("DEFAULT").get(null)
                    val layoutResultClass = cl.loadClass("dev.kuml.layout.LayoutResult")
                    val themeClass = cl.loadClass("dev.kuml.renderer.theme.core.KumlTheme")
                    val toSvgMethod =
                        svgRendererClass.getMethod(
                            "toSvg",
                            diagram::class.java,
                            layoutResultClass,
                            themeClass,
                            svgRenderOptionsClass,
                        )
                    toSvgMethod.invoke(null, diagram, layoutResult, theme, optionsDefault) as? String
                }
                else ->
                    // C4 and SysML 2 preview — return null for V2.0.30 MVP;
                    // full C4/SysML2 preview wiring is V2.x.
                    null
            }
        } catch (_: Throwable) {
            null
        }
}
