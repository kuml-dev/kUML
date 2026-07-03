package dev.kuml.layout.grid

import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.KumlLayoutEngineProvider
import dev.kuml.layout.LayoutEngineId

/**
 * ServiceLoader-Provider für die [GridLayoutEngine].
 *
 * Wird über `META-INF/services/dev.kuml.layout.KumlLayoutEngineProvider`
 * registriert. Klienten holen die Engine via
 * `LayoutEngineRegistry.get("kuml.grid")` oder
 * `LayoutEngineRegistry.pickFor(diagramKind)`.
 */
public class GridLayoutEngineProvider : KumlLayoutEngineProvider {
    public override val id: LayoutEngineId = LayoutEngineId("kuml.grid")

    public override fun engine(): KumlLayoutEngine = GridLayoutEngine()
}
