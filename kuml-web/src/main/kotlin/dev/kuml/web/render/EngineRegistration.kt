package dev.kuml.web.render

import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import dev.kuml.renderer.theme.core.ThemeRegistry

/**
 * Idempotent registration of layout engines and themes.
 *
 * Call [ensure] once at server startup (in [dev.kuml.web.KumlWebServer] module).
 * Safe to call multiple times — checks prevent double-registration.
 */
internal object EngineRegistration {
    fun ensure() {
        if (LayoutEngineRegistry.ids().isEmpty()) {
            // Grid first so pickFor(kind, null) prefers grid for class/component/use-case diagrams
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
        }
        if (ThemeRegistry.names().isEmpty()) {
            ThemeRegistry.loadFromClasspath()
        }
    }
}
