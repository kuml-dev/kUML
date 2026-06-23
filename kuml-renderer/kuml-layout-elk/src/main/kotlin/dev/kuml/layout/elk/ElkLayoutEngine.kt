package dev.kuml.layout.elk

import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutCapabilities
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.LayoutWarning
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.util.BasicProgressMonitor

/**
 * `KumlLayoutEngine`-Implementierung auf Basis von Eclipse Layout Kernel (ELK) `elk.layered`.
 *
 * Dies ist die V1-Standard-Engine für alle Box-und-Kante-Diagrammtypen (UML-Klassen,
 * UML-Komponenten, UML-Use-Case, UML-Zustand sowie alle C4-Typen und Generic).
 *
 * **Designprinzipien (aus dem Designentwurf):**
 * - Stateless und thread-safe: Jeder [layout]-Aufruf baut einen frischen ELK-Graphen.
 * - Kein ELK-Typ in Public-Signaturen: alle ELK-Importe verbleiben in `internal`-Code.
 * - Nicht unterstützte [LayoutHints] (Grid, Pinned, Relative) werden als [LayoutWarning]
 *   zurückgegeben, nicht als Exception.
 * - `capabilities.deterministic = false`: ELK gibt keine Bit-Stabilität über JVM-Versionen
 *   hinweg — deshalb bleibt `seed` im Ergebnis immer `null`.
 *
 * **Nicht unterstützte Diagrammtypen:**
 * UML-Sequenzdiagramme ([DiagramKind.UmlSequence]) sind nicht in [capabilities] enthalten —
 * ELK ist für sequentielle Nachrichtenflüsse ungeeignet (eigene Pipeline folgt in V1.1).
 *
 * Spec: `03 Bereiche/kUML/Plan/Phase 1 — ELK-Adapter (Designentwurf).md`
 * ADR: ADR-0006 — Eigene Grid-Layout-Engine neben ELK
 *
 * @param configuration Feinabstimmungs-Parameter für den ELK-Algorithmus.
 */
public class ElkLayoutEngine(
    private val configuration: ElkEngineConfiguration = ElkEngineConfiguration.DEFAULT,
) : KumlLayoutEngine {
    init {
        // ELK uses a service-loader-like mechanism to register algorithm metadata.
        // In OSGi environments this happens automatically; in plain JVM we must
        // call registerLayoutMetaDataProviders explicitly.
        LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(
            LayeredMetaDataProvider(),
        )
    }

    /** Stabile ID dieser Engine: `"elk.layered"`. */
    public override val id: LayoutEngineId = LayoutEngineId("elk.layered")

    /**
     * Maschinenlesbare Fähigkeiten dieser Engine.
     *
     * Unterstützte Diagrammtypen: UML (Class, Component, UseCase, State) + alle C4-Varianten +
     * Generic. UmlSequence ist bewusst NICHT enthalten.
     * Edge-Stile: Direct und OrthogonalRounded (Rundung `cornerRadiusPx = 0f`, Renderer rundet).
     */
    public override val capabilities: LayoutCapabilities =
        LayoutCapabilities(
            deterministic = false,
            supportedDiagramKinds =
                setOf(
                    DiagramKind.UmlClass,
                    DiagramKind.UmlComponent,
                    DiagramKind.UmlUseCase,
                    DiagramKind.UmlState,
                    DiagramKind.C4Context,
                    DiagramKind.C4Container,
                    DiagramKind.C4Component,
                    DiagramKind.C4Deployment,
                    DiagramKind.C4Landscape,
                    DiagramKind.Generic,
                ),
            supportedEdgeStyles = setOf(EdgeRouteStyle.Direct, EdgeRouteStyle.OrthogonalRounded),
            respectsGridHints = false,
            respectsRelativeConstraints = false,
            maxRecommendedNodes = 500,
        )

    /**
     * Berechnet das Layout für [graph] gemäß [hints].
     *
     * Die Übersetzungspipeline:
     * 1. [ElkGraphBuilder] — `LayoutGraph` → ELK-Knotenbaum
     * 2. [HintsMapper] — `LayoutHints` → ELK-LayoutOptions; nicht unterstützte Hints → [LayoutWarning]
     * 3. `RecursiveGraphLayoutEngine.layout()` — ELK berechnet Positionen
     * 4. [ResultMapper] — ELK-Ergebnis → `LayoutResult`
     *
     * @param graph Der zu layoutende Diagrammgraph.
     * @param hints Steuerparameter; Defaults in [LayoutHints.DEFAULT].
     * @return Vollständiges Layout-Ergebnis mit absoluten Positionen und Routing-Pfaden.
     */
    public override fun layout(
        graph: LayoutGraph,
        hints: LayoutHints,
    ): LayoutResult {
        val warnings = mutableListOf<LayoutWarning>()

        // 1. Build ELK graph
        val builder = ElkGraphBuilder(graph)
        val root = builder.build()

        // 2. Apply hints and configuration
        warnings += HintsMapper.applyGlobalHints(root, hints, configuration)
        warnings += HintsMapper.collectNodeHintWarnings(graph)
        HintsMapper.applyGroupPadding(builder, hints, configuration)

        // 3. Time-budget tracking
        val startMs = System.currentTimeMillis()

        // 4. Run ELK layout
        val engine = RecursiveGraphLayoutEngine()
        engine.layout(root, BasicProgressMonitor())

        // 5. Check time budget
        val elapsedMs = System.currentTimeMillis() - startMs
        if (elapsedMs > hints.timeBudgetMillis) {
            warnings.add(
                LayoutWarning(
                    code = "time.budget.exceeded",
                    message =
                        "ELK layout took ${elapsedMs}ms, exceeding the soft budget of " +
                            "${hints.timeBudgetMillis}ms. Result is still complete.",
                ),
            )
        }

        // 6. Map result back
        return ResultMapper.toLayoutResult(
            engineId = id,
            builder = builder,
            root = root,
            warnings = warnings,
            nodeCount = graph.nodes.size,
        )
    }
}
