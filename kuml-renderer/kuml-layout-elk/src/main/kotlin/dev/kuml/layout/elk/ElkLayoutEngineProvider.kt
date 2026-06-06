package dev.kuml.layout.elk

import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.KumlLayoutEngineProvider
import dev.kuml.layout.LayoutEngineId

/**
 * ServiceLoader-Provider für die [ElkLayoutEngine].
 *
 * Wird über `META-INF/services/dev.kuml.layout.KumlLayoutEngineProvider`
 * registriert. Klienten holen die Engine via
 * `LayoutEngineRegistry.get("elk.layered")` oder
 * `LayoutEngineRegistry.pickFor(diagramKind)`.
 *
 * Die ELK-Engine ist die Default-Wahl für die meisten Diagrammtypen, weil
 * sie ausgereiftes Sugiyama-Layered-Layouting + Channel-Routing mitbringt.
 * Die [dev.kuml.layout.grid.GridLayoutEngine] (V1.1.12) ist eine pure-Kotlin-
 * Alternative für GraalVM-Native-Image-Builds und für deterministisch hint-
 * basierte Layouts.
 */
public class ElkLayoutEngineProvider : KumlLayoutEngineProvider {
    public override val id: LayoutEngineId = LayoutEngineId("elk.layered")

    public override fun engine(): KumlLayoutEngine = ElkLayoutEngine()
}
