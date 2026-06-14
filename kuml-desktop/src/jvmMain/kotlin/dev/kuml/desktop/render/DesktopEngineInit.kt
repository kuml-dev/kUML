package dev.kuml.desktop.render

import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import dev.kuml.renderer.theme.core.ThemeRegistry

internal object DesktopEngineInit {
    @Volatile private var initialized = false

    fun ensure() {
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
