package dev.kuml.layout.elk

import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutWarning
import dev.kuml.layout.NodeId
import org.eclipse.elk.alg.layered.options.CrossingMinimizationStrategy
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.graph.ElkNode

/**
 * Bildet [LayoutHints] und [dev.kuml.layout.NodeHints] auf ELK-Layout-Optionen ab.
 *
 * Hints, die ELK nicht unterstützt (Grid, Pinned, Relative), werden ignoriert
 * und als [LayoutWarning] gesammelt. Kein ELK-Typ verlässt dieses Objekt.
 */
internal object HintsMapper {
    /**
     * Wendet globale [LayoutHints] und [ElkEngineConfiguration] auf den ELK-Root-Knoten an.
     * Gibt eine Liste von [LayoutWarning]s für unbekannte Engine-Optionen zurück.
     */
    fun applyGlobalHints(
        root: ElkNode,
        hints: LayoutHints,
        config: ElkEngineConfiguration,
    ): List<LayoutWarning> {
        val warnings = mutableListOf<LayoutWarning>()

        // Algorithm
        root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)

        // Direction
        root.setProperty(
            CoreOptions.DIRECTION,
            when (hints.direction) {
                LayoutDirection.TopToBottom -> Direction.DOWN
                LayoutDirection.BottomToTop -> Direction.UP
                LayoutDirection.LeftToRight -> Direction.RIGHT
                LayoutDirection.RightToLeft -> Direction.LEFT
            },
        )

        // Edge routing style
        root.setProperty(
            CoreOptions.EDGE_ROUTING,
            when (hints.defaultEdgeStyle) {
                EdgeRouteStyle.Direct -> EdgeRouting.POLYLINE
                EdgeRouteStyle.OrthogonalRounded -> EdgeRouting.ORTHOGONAL
                // ELK doesn't natively support TreeRounded or Bezier;
                // fall back to ORTHOGONAL and note in docs
                EdgeRouteStyle.TreeRounded -> EdgeRouting.ORTHOGONAL
                EdgeRouteStyle.Bezier -> EdgeRouting.SPLINES
            },
        )

        // Node-to-node spacing (overridable via hints.spacing, then from config default)
        val nodeSpacing = hints.spacing.nodeToNode
        root.setProperty(CoreOptions.SPACING_NODE_NODE, nodeSpacing.toDouble())

        // Edge-to-edge spacing
        root.setProperty(CoreOptions.SPACING_EDGE_EDGE, hints.spacing.edgeToEdge.toDouble())

        // Layer spacing (between layers) — V2.0.45: per-diagram hint
        // overrides the engine default. `hints.spacing.layerToLayer` is
        // `NaN` when no override was set (DEFAULT case), in which case we
        // fall back to `config.layerSpacing`. Required so SysML-2 ACT
        // diagrams can request roomier vertical layer gaps without bumping
        // the global ELK config (which would also affect UML / C4).
        val layerSpacing =
            hints.spacing.layerToLayer.takeUnless { it.isNaN() } ?: config.layerSpacing
        root.setProperty(
            LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS,
            layerSpacing.toDouble(),
        )

        // Edge-to-node spacing
        root.setProperty(
            CoreOptions.SPACING_EDGE_NODE,
            config.edgeNodeSpacing.toDouble(),
        )

        // V11.x — Stub-Länge zwischen einem Knoten und dem ersten/letzten
        // Bend der orthogonalen Edge-Route. `CoreOptions.SPACING_EDGE_NODE`
        // schützt nur den Abstand zu *nicht-adjazenten* Knoten; die Stub-
        // Länge an Source/Target wird in ELK Layered durch diese
        // spezifischere Option gesteuert. Ohne sie legt ELK den horizontalen
        // Joiner einer L-Route ~10 px unter die Source-Bottom-Edge, wodurch
        // das Edge-Label auf der Source-Box-Beschreibung landet (siehe
        // C4-Context Internet-Banking-Beispiel, V11.x-Validierung).
        //
        // Wert = max(edgeNodeSpacing, 25) — gleicher Faden wie der globale
        // Edge-Node-Abstand, aber nie unter 25 px, damit Edge-Label-Halos
        // (~14 px Texthöhe + 4 px Halo) sicher in den Korridor passen.
        root.setProperty(
            LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS,
            maxOf(config.edgeNodeSpacing, 25f).toDouble(),
        )

        // Group padding (applied to each group node individually, set globally here as default)
        val pad = hints.spacing.groupPadding.toDouble()
        root.setProperty(CoreOptions.PADDING, ElkPadding(pad))

        // Crossing minimization strategy
        root.setProperty(
            LayeredOptions.CROSSING_MINIMIZATION_STRATEGY,
            when (config.crossingMinimizationStrategy) {
                CrossingMinimization.LayerSweep -> CrossingMinimizationStrategy.LAYER_SWEEP
                CrossingMinimization.Interactive -> CrossingMinimizationStrategy.INTERACTIVE
            },
        )

        // V2.x — Merge edges that share a target/source port to consolidate
        // visual "fan-in" / "fan-out" bundles (z.B. 18 Generalisierungen, die
        // alle auf `AbstractTable` zeigen, oder N Foreign-Keys, die ein
        // gemeinsames Stamm-Segment teilen). Opt-in über [LayoutHints.mergeEdges];
        // Default: false, weil Trunk-Routing andere Diagramme schwerer lesbar
        // machen kann (Strang läuft durch Whitespace zwischen Klassen).
        root.setProperty(LayeredOptions.MERGE_EDGES, hints.mergeEdges)

        // Engine escape-hatch options (raw key/value strings)
        for ((key, value) in hints.engineOptions) {
            val resolved = resolveEngineOption(key, value, root)
            if (!resolved) {
                warnings.add(
                    LayoutWarning(
                        code = "engine.option.unknown",
                        message = "Unknown ELK option key '$key' — ignored.",
                    ),
                )
            }
        }

        return warnings
    }

    /**
     * Sammelt [LayoutWarning]s für nicht unterstützte [dev.kuml.layout.NodeHints] im [graph].
     * Diese Hints werden nicht angewendet (ELK kennt kein Grid-Layout).
     */
    fun collectNodeHintWarnings(graph: LayoutGraph): List<LayoutWarning> {
        val warnings = mutableListOf<LayoutWarning>()

        for (node in graph.nodes) {
            val hints = node.hints
            val id = node.id

            if (hints.gridCol != null || hints.gridRow != null) {
                warnings.add(gridWarning(id))
            }
            if (hints.pinned) {
                warnings.add(
                    LayoutWarning(
                        code = "hint.ignored.pinned",
                        message = "Node '${id.value}' has pinned=true which ELK does not support — ignored.",
                        affectedNodes = listOf(id),
                    ),
                )
            }
            if (hints.relative.isNotEmpty()) {
                warnings.add(
                    LayoutWarning(
                        code = "hint.ignored.relative",
                        message = "Node '${id.value}' has relative constraints which ELK does not support — ignored.",
                        affectedNodes = listOf(id),
                    ),
                )
            }
        }

        return warnings
    }

    /**
     * Applies padding from a [LayoutGraph]'s groups onto their corresponding ELK nodes.
     *
     * For groups that opted into compound layout (`layoutAsCompound = true`), this also
     * enables `HIERARCHY_HANDLING = INCLUDE_CHILDREN` on the root so ELK routes edges
     * that cross the compound boundary (e.g. an Actor → UseCase association where the
     * UseCase is inside the system-boundary subject) and edges whose endpoints are both
     * children of the compound (e.g. `«include»` / `«extend»` between two UseCases
     * inside the same subject). Without this flag, ELK leaves cross-hierarchy edges
     * with empty edge sections (rendered as degenerated point lines at the canvas
     * origin) and intra-compound edges are routed by the compound's child layout but
     * their bend points remain relative to the compound, which only resolves cleanly
     * when the result mapper also translates them to absolute coordinates.
     */
    fun applyGroupPadding(builder: ElkGraphBuilder) {
        var anyCompound = false
        for (group in builder.groups()) {
            val elkGroup = builder.groupMap[group.id] ?: continue
            val p = group.padding
            elkGroup.setProperty(
                CoreOptions.PADDING,
                ElkPadding(p.top.toDouble(), p.right.toDouble(), p.bottom.toDouble(), p.left.toDouble()),
            )
            if (group.layoutAsCompound) anyCompound = true
        }
        if (anyCompound) {
            // Walk up to the ELK root and enable inter-hierarchy edge routing.
            var root: ElkNode? = builder.groupMap.values.firstOrNull()
            while (root?.parent != null) root = root.parent
            root?.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun gridWarning(id: NodeId): LayoutWarning =
        LayoutWarning(
            code = "hint.ignored.grid",
            message = "Node '${id.value}' has grid hints (gridCol/gridRow) which ELK does not support — ignored.",
            affectedNodes = listOf(id),
        )

    /**
     * Attempts to apply a raw engine option string to the root node.
     * Returns true if the key was recognized, false otherwise.
     *
     * Currently a stub — a full implementation would parse the ELK option registry.
     * Unknown keys are surfaced as warnings.
     */
    private fun resolveEngineOption(
        @Suppress("UNUSED_PARAMETER") key: String,
        @Suppress("UNUSED_PARAMETER") value: String,
        @Suppress("UNUSED_PARAMETER") root: ElkNode,
    ): Boolean = false
}
