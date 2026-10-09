package dev.kuml.desktop.render

import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import dev.kuml.renderer.theme.core.ThemeRegistry

internal object DesktopEngineInit {
    /**
     * Idempotent and cheap: re-checks the registries on every call instead of latching a
     * one-shot flag, so a registry that was emptied afterwards (tests that clear
     * [ThemeRegistry], a plugin reload) is repopulated by the next render rather than leaving
     * every later render stuck on "Theme not found".
     */
    @Synchronized
    fun ensure() {
        if (LayoutEngineRegistry.ids().isEmpty()) {
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
        }
        if (ThemeRegistry.names().isEmpty()) {
            ThemeRegistry.loadFromClasspath()
        }
    }
}
