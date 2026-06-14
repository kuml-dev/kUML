package dev.kuml.desktop.render

sealed class DesktopRenderResult {
    data class Svg(val svg: String) : DesktopRenderResult()
    data class Error(val message: String) : DesktopRenderResult()
}
