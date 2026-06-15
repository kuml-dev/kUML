package dev.kuml.io.svg.uml

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.uml.UmlComponent
import java.util.Locale

/**
 * V2.0.47 — Kurznotation der UML-Komponenten-Schnittstellen ("Lollipop" für
 * `provides`, "Socket" für `requires`).
 *
 * Wird gezeichnet, wenn eine Interface-ID in
 * [UmlComponent.providedInterfaceIds] / [UmlComponent.requiredInterfaceIds]
 * referenziert wird, das Interface aber **nicht** als eigener Knoten im
 * Diagramm vorhanden ist. In diesem Fall hängt der Renderer ein kleines
 * Symbol an die obere Kante der Komponente — UML-Standardnotation für
 * "Vertrag ohne separate Interface-Box":
 *
 *  - **Lollipop** (provides) — voller Kreis am Ende eines kurzen Stab-Stubs,
 *    mit Interface-Name als Label darüber.
 *  - **Socket** (requires) — Halbkreis (offene Schale, Öffnung nach unten
 *    zur Komponente), Label darüber.
 *
 * Bei mehreren ungebundenen Verträgen werden die Symbole gleichmäßig
 * horizontal entlang der Oberkante verteilt — `provides` zuerst (links),
 * `requires` danach (rechts).
 *
 * Geltungsbereich des "Kurz vs. Explizit"-Schalters: liegt eine
 * `UmlInterface` mit der Ziel-ID **als sichtbarer Knoten** im Diagramm vor,
 * synthetisiert [dev.kuml.uml.dsl.ComponentDiagramBuilder] stattdessen eine
 * `UmlInterfaceRealization` bzw. `UmlDependency` und der Renderer überspringt
 * das Lollipop/Socket-Symbol für diese ID — sonst hätte man beides parallel
 * gezeichnet.
 *
 * Vault-Bezug: [[03 Bereiche/kUML/Beispiele/12 UML Component – Order Architecture]].
 */
internal object UmlComponentContracts {
    /** Vertikale Stab-Länge vom oberen Komponentenrand bis zum Symbolmittelpunkt-Tangentialpunkt. */
    internal const val STUB_PX: Float = 16f

    /** Radius des Lollipop-Kreises bzw. des Socket-Halbkreises. */
    internal const val RADIUS_PX: Float = 8f

    /** Vertikaler Abstand zwischen Symbol-Oberkante und Label-Baseline. */
    internal const val LABEL_GAP_PX: Float = 4f

    /**
     * Geschätzte Label-Texthöhe in Pixeln (Schriftgröße 9 px aus `.kuml-port-label`).
     * Wird für das Padding-Reserve gebraucht, damit das Label nicht oberhalb der
     * Canvas-Oberkante abgeschnitten wird.
     */
    internal const val LABEL_HEIGHT_PX: Float = 11f

    /**
     * Gesamte Höhe, um die ein Vertragssymbol über die Komponenten-Oberkante
     * hinausragt — Stub + Symbolkugel + Label-Gap + Labelhöhe. Wird vom
     * Renderer benutzt, um `paddingPx` mindestens darauf anzuheben, damit
     * weder Kugel noch Label oberhalb der `viewBox`-Oberkante landen.
     */
    internal const val TOTAL_UPWARD_EXTENT_PX: Float =
        STUB_PX + 2f * RADIUS_PX + LABEL_GAP_PX + LABEL_HEIGHT_PX

    /**
     * Liefert `true`, wenn die Komponente mindestens ein `provides`/`requires`
     * hat, dessen Interface-ID nicht im Knoten-Index ist — also mindestens
     * ein Vertragssymbol gerendert werden würde.
     */
    fun hasUnboundContracts(
        component: UmlComponent,
        isDiagramNode: (String) -> Boolean,
    ): Boolean =
        component.providedInterfaceIds.any { !isDiagramNode(it) } ||
            component.requiredInterfaceIds.any { !isDiagramNode(it) }

    /**
     * Rendert alle ungebundenen Lollipop/Socket-Symbole für [component] in
     * absoluten Canvas-Koordinaten (inkl. Padding-Shift, falls aufrufender
     * Renderer bereits geshiftet hat).
     *
     * @param layout Bounding-Box der Komponente — gleiche Box, die
     *        [renderUmlComponent] zeichnet.
     * @param isDiagramNode Liefert `true`, wenn eine ID im Diagramm als
     *        Knoten existiert (dann wird das Vertragssymbol weggelassen, weil
     *        die explizite Realization-/Dependency-Kante das schon abdeckt).
     */
    fun render(
        component: UmlComponent,
        layout: NodeLayout,
        isDiagramNode: (String) -> Boolean,
        builder: SvgBuilder,
    ) {
        val unboundProvides = component.providedInterfaceIds.filterNot(isDiagramNode)
        val unboundRequires = component.requiredInterfaceIds.filterNot(isDiagramNode)
        if (unboundProvides.isEmpty() && unboundRequires.isEmpty()) return

        val bx = layout.bounds.origin.x
        val by = layout.bounds.origin.y
        val w = layout.bounds.size.width

        val total = unboundProvides.size + unboundRequires.size
        builder.tag(
            "g",
            mapOf("id" to xmlEscapeAttr("contracts-${component.id}")),
        ) {
            // V2.0.47 — Symbole werden gleichmäßig entlang der Oberkante
            //           verteilt: Slot i sitzt bei x = bx + w * (i+1) / (n+1).
            //           Reihenfolge: provides zuerst (links), requires danach
            //           (rechts). Falls nur ein Symbol vorhanden ist, landet
            //           es zentriert über der Komponente.
            unboundProvides.forEachIndexed { i, ifaceId ->
                val slotX = bx + w * (i + 1) / (total + 1)
                renderLollipop(this, slotX, by, ifaceId)
            }
            unboundRequires.forEachIndexed { i, ifaceId ->
                val slotIdx = unboundProvides.size + i
                val slotX = bx + w * (slotIdx + 1) / (total + 1)
                renderSocket(this, slotX, by, ifaceId)
            }
        }
    }

    /**
     * Zeichnet einen Lollipop (Vollkreis am Stab-Ende) zentriert bei [x] über
     * der Komponenten-Oberkante [anchorY].
     */
    private fun renderLollipop(
        builder: SvgBuilder,
        x: Float,
        anchorY: Float,
        label: String,
    ) {
        val cy = anchorY - STUB_PX - RADIUS_PX
        val stubTop = anchorY - STUB_PX
        builder.tag(
            "line",
            mapOf(
                "x1" to fmt(x),
                "y1" to fmt(anchorY),
                "x2" to fmt(x),
                "y2" to fmt(stubTop),
                "class" to "kuml-edge",
            ),
        )
        builder.tag(
            "circle",
            mapOf(
                "cx" to fmt(x),
                "cy" to fmt(cy),
                "r" to fmt(RADIUS_PX),
                "class" to "kuml-interface",
            ),
        )
        builder.tag(
            "text",
            mapOf(
                "class" to "kuml-port-label",
                "x" to fmt(x),
                "y" to fmt(cy - RADIUS_PX - LABEL_GAP_PX),
                "text-anchor" to "middle",
            ),
        ) { text(label) }
    }

    /**
     * Zeichnet einen Socket (Halbkreis-Schale, Öffnung VOM Komponentenrand
     * WEG nach oben) zentriert bei [x] über [anchorY].
     *
     * UML-Konvention: der Socket „empfängt" einen potenziellen Ball
     * (Lollipop), seine Öffnung zeigt also in die Richtung, aus der dieser
     * Ball kommen würde — nach außen, nicht zur Komponente. Der Stub
     * berührt den tiefsten Punkt der Mulde (Boden) und führt von dort
     * senkrecht zur Komponentenkante.
     */
    private fun renderSocket(
        builder: SvgBuilder,
        x: Float,
        anchorY: Float,
        label: String,
    ) {
        val cy = anchorY - STUB_PX - RADIUS_PX
        val stubTop = anchorY - STUB_PX
        builder.tag(
            "line",
            mapOf(
                "x1" to fmt(x),
                "y1" to fmt(anchorY),
                "x2" to fmt(x),
                "y2" to fmt(stubTop),
                "class" to "kuml-edge",
            ),
        )
        // V2.0.48 — Sweep-Flag von 1 auf 0 geändert nach Vault-Feedback zur
        // Schale, die in V2.0.47 falsch herum gezeichnet wurde. In SVG-
        // Koordinaten (y wächst nach unten) ist sweep=0 = gegen den
        // Uhrzeigersinn von (x-r, cy) nach (x+r, cy), was den Bogen über die
        // UNTERE Kreishälfte führt — die Mulde wölbt sich also nach UNTEN
        // (zur Komponente hin), die Öffnung (Chord) liegt OBEN. Standard-
        // UML-Socket: bereit, einen von außen kommenden Lollipop zu
        // umfassen. Der Stub landet im tiefsten Punkt bei (x, cy + r) =
        // (x, stubTop).
        val pathD =
            "M ${fmt(x - RADIUS_PX)} ${fmt(cy)} " +
                "A ${fmt(RADIUS_PX)} ${fmt(RADIUS_PX)} 0 0 0 " +
                "${fmt(x + RADIUS_PX)} ${fmt(cy)}"
        builder.tag(
            "path",
            mapOf(
                "d" to pathD,
                "class" to "kuml-actor",
            ),
        )
        builder.tag(
            "text",
            mapOf(
                "class" to "kuml-port-label",
                "x" to fmt(x),
                "y" to fmt(cy - RADIUS_PX - LABEL_GAP_PX),
                "text-anchor" to "middle",
            ),
        ) { text(label) }
    }

    private fun fmt(v: Float): String {
        val i = v.toInt()
        return if (v == i.toFloat()) "$i" else "%.2f".format(Locale.ROOT, v)
    }
}
