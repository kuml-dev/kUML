package dev.kuml.desktop.render

sealed class DesktopRenderResult {
    data class Svg(
        val svg: String,
        // V3.x — Live-Simulation: whether this diagram type (UML STATE, SysML-2 STM) can be
        // simulated. Defaults to false so every pre-existing caller/`is Svg ->` destructuring
        // in this codebase stays source-compatible.
        val simulatable: Boolean = false,
    ) : DesktopRenderResult()

    data class Error(
        val message: String,
    ) : DesktopRenderResult()
}
