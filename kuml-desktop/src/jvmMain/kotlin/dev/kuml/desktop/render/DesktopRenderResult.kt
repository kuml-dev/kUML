package dev.kuml.desktop.render

sealed class DesktopRenderResult {
    data class Svg(
        val svg: String,
        // V3.x — Live-Simulation: whether this diagram type (UML STATE, SysML-2 STM) can be
        // simulated. Defaults to false so every pre-existing caller/`is Svg ->` destructuring
        // in this codebase stays source-compatible.
        val simulatable: Boolean = false,
        // Whether the LaTeX export can handle this diagram (see isLatexCapable). Defaults to false for the
        // same source-compatibility reason as [simulatable]; the UI cannot derive the family from the SVG.
        val latexCapable: Boolean = false,
        /** Non-fatal note diagnostics (`[KUML-NOTE-W001] ...`); the diagram is still drawn. */
        val warnings: List<String> = emptyList(),
    ) : DesktopRenderResult()

    data class Error(
        val message: String,
    ) : DesktopRenderResult()
}
