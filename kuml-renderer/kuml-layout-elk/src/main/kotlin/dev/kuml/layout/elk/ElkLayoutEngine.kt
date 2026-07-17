package dev.kuml.layout.elk

import dev.kuml.layout.DiagramKind
import dev.kuml.layout.EdgeRouteStyle
import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
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
import org.eclipse.elk.graph.ElkNode

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
     * **[dev.kuml.layout.LayoutGroup.minSize] re-layout loop (V3.1.x):** compound groups
     * (`layoutAsCompound = true`) that declare a `minSize` (e.g. a wide title on an expanded
     * BPMN SubProcess frame) are sized by ELK *purely* from their children's laid-out bounding
     * box + padding — ELK's own size-constraint machinery does not apply to compound nodes
     * (see [HintsMapper.applyGroupPadding]'s NOTE). If a compound node with few/small children
     * needs to be wider/taller than its children alone would produce, simply *reporting* a
     * wider box after the fact (post-layout, symmetrically around the ELK-computed center)
     * would leave ELK unaware of the extra space during the actual layout pass — it would not
     * have reserved room for it, so the widened frame can overlap a sibling node/group that ELK
     * placed right next to it (confirmed regression: a gateway's two parallel branches, one an
     * expanded SubProcess with a long name and few children, the other a plain sibling task —
     * the widened frame ate into the sibling's box). Instead, steps 1–4 below run in a loop
     * (bounded by [MAX_MIN_SIZE_ATTEMPTS]): after each pass, any compound group whose raw ELK
     * size still falls short of its `minSize` gets extra padding equal to the deficit (split
     * evenly across both affected sides) and the *entire graph is rebuilt and laid out again*
     * — so ELK's own bounding-box-plus-padding sizing already produces the wider/taller box
     * *before* the outer layout positions that compound's siblings, and space is reserved
     * for real instead of clawed back afterwards.
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

        var extraPaddingByGroup: Map<GroupId, Insets> = emptyMap()
        lateinit var builder: ElkGraphBuilder
        lateinit var root: ElkNode
        var totalElapsedMs = 0L
        var attempt = 0

        while (true) {
            attempt++

            // 1. Build a fresh ELK graph — [ElkGraphBuilder] is single-use per layout run
            // (KDoc: "Jede Instanz ist für genau einen Layout-Lauf gedacht"), and a previous
            // attempt's ELK graph has already been mutated in place by `engine.layout()`, so
            // a re-layout with different padding needs to start from a clean graph.
            builder = ElkGraphBuilder(graph)
            root = builder.build()

            // 2. Apply hints and configuration (deterministic given `graph`/`hints`, so the
            // resulting warnings are the same on every attempt — only the last attempt's are
            // kept below).
            val passWarnings = mutableListOf<LayoutWarning>()
            passWarnings += HintsMapper.applyGlobalHints(root, hints, configuration)
            passWarnings += HintsMapper.collectNodeHintWarnings(graph)
            HintsMapper.applyGroupPadding(builder, hints, configuration, extraPaddingByGroup)

            // 3. Time-budget tracking (accumulated across all re-layout attempts so the
            // warning reflects the real total work performed for this [layout] call).
            val startMs = System.currentTimeMillis()

            // 4. Run ELK layout
            val engine = RecursiveGraphLayoutEngine()
            engine.layout(root, BasicProgressMonitor())

            totalElapsedMs += System.currentTimeMillis() - startMs

            val deficits = minSizeDeficits(builder)
            val isLastAttempt = attempt >= MAX_MIN_SIZE_ATTEMPTS
            if (deficits.isEmpty() || isLastAttempt) {
                warnings += passWarnings
                if (deficits.isNotEmpty()) {
                    // Attempt cap hit while a gap still remains — ResultMapper's defensive
                    // post-layout floor takes over from here for the affected group(s), which
                    // re-introduces the (now rare) risk of a sibling overlap this loop exists
                    // to avoid. Surface it as a warning so callers can notice.
                    warnings.add(
                        LayoutWarning(
                            code = "group.minSize.unresolved",
                            message =
                                "Compound group(s) ${deficits.keys.joinToString { it.value }} still " +
                                    "fall short of their declared minSize after $MAX_MIN_SIZE_ATTEMPTS " +
                                    "re-layout attempt(s); falling back to a post-layout bounds floor " +
                                    "that may overlap a sibling node/group.",
                        ),
                    )
                }
                break
            }

            // Retry with the deficit folded into the padding of the affected group(s) so the
            // next pass's ELK-computed compound size already satisfies minSize.
            extraPaddingByGroup = mergeInsets(extraPaddingByGroup, deficits)
        }

        // 5. Check time budget
        if (totalElapsedMs > hints.timeBudgetMillis) {
            warnings.add(
                LayoutWarning(
                    code = "time.budget.exceeded",
                    message =
                        "ELK layout took ${totalElapsedMs}ms, exceeding the soft budget of " +
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

    /**
     * For every compound group ([dev.kuml.layout.LayoutGroup.layoutAsCompound]) with a
     * declared [dev.kuml.layout.LayoutGroup.minSize], compares that floor against the
     * group's *actual* ELK-computed size after a layout pass and returns the extra
     * padding (split evenly across both affected sides) needed to close the gap.
     * Groups that already meet or exceed `minSize` are omitted.
     */
    private fun minSizeDeficits(builder: ElkGraphBuilder): Map<GroupId, Insets> {
        var result: MutableMap<GroupId, Insets>? = null
        for (group in builder.groups()) {
            if (!group.layoutAsCompound) continue
            val minSize = group.minSize ?: continue
            val elkGroup = builder.groupMap[group.id] ?: continue
            val deficitW = (minSize.width - elkGroup.width.toFloat()).coerceAtLeast(0f)
            val deficitH = (minSize.height - elkGroup.height.toFloat()).coerceAtLeast(0f)
            if (deficitW <= 0f && deficitH <= 0f) continue
            (result ?: mutableMapOf<GroupId, Insets>().also { result = it })[group.id] =
                Insets(
                    top = deficitH / 2f,
                    right = deficitW / 2f,
                    bottom = deficitH / 2f,
                    left = deficitW / 2f,
                )
        }
        return result ?: emptyMap()
    }

    /** Element-wise sum of two `GroupId → Insets` maps, treating a missing key as [Insets.ZERO]. */
    private fun mergeInsets(
        a: Map<GroupId, Insets>,
        b: Map<GroupId, Insets>,
    ): Map<GroupId, Insets> =
        (a.keys + b.keys).associateWith { key ->
            val ai = a[key] ?: Insets.ZERO
            val bi = b[key] ?: Insets.ZERO
            Insets(
                top = ai.top + bi.top,
                right = ai.right + bi.right,
                bottom = ai.bottom + bi.bottom,
                left = ai.left + bi.left,
            )
        }

    private companion object {
        /**
         * Upper bound on [dev.kuml.layout.LayoutGroup.minSize] re-layout attempts (see
         * [layout]'s KDoc). One padding-adjustment pass is normally enough to close the gap
         * exactly (padding only offsets a compound's content, it does not change the
         * children's own laid-out size), so this generous cap exists purely as a safety net
         * against pathological/non-converging cases, not as an expected steady state.
         */
        const val MAX_MIN_SIZE_ATTEMPTS = 4
    }
}
