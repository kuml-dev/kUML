package dev.kuml.jetbrains

import dev.kuml.core.script.KumlScriptHost

/**
 * Thin rendering facade used by [KumlPreviewPanel].
 *
 * Decoupled from the panel so unit tests can test debounce + status logic
 * independently of the full kUML rendering pipeline. The heavy coupling to
 * [KumlScriptHost] lives here and can be swapped or mocked at the call site.
 *
 * V2.0.28b ships with a "no-op stub" body that returns `null` because the
 * full render pipeline (KumlScriptHost.eval → DiagramExtractor.extractAny →
 * layout → KumlSvgRenderer.toSvg) has a large transitive footprint and
 * requires classpath wiring that is only set up correctly inside the running
 * IntelliJ process (via [KumlScriptDefinitionsProvider]). Wiring the live
 * render path through the preview panel is V2.0.28c work once the IDE
 * classpath is verified end-to-end.
 *
 * The stub ensures:
 *  - No compile-time coupling on classes not on the plugin classpath.
 *  - `KumlPreviewPanel` unit tests pass without a running IDE.
 *  - The status-label / debounce / dispose behavior is testable in isolation.
 */
internal object KumlPreviewRenderer {
    /**
     * Render the given `.kuml.kts` [scriptText] to an SVG string.
     *
     * Returns `null` if the script has no renderable diagram, compilation fails,
     * or the renderer is not yet fully wired (V2.0.28b stub).
     *
     * @param scriptText Full text of the `.kuml.kts` file.
     * @param scriptName A name / path used for diagnostics.
     */
    fun render(
        scriptText: String,
        scriptName: String,
    ): String? {
        // V2.0.28b: stub — live rendering wired in V2.0.28c.
        // We call KumlScriptHost.eval to at least validate compilation, but
        // we don't attempt layout or SVG emission because that requires the
        // full plugin classpath to be set up.
        return try {
            val result = KumlScriptHost.eval(scriptText, scriptName)
            // If compilation succeeded but we can't emit SVG yet, return null
            // so the panel shows "No diagram" rather than crashing.
            if (result.reports.none {
                    it.severity >= kotlin.script.experimental.api.ScriptDiagnostic.Severity.ERROR
                }
            ) {
                null // V2.0.28c: replace with actual SVG emission
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
