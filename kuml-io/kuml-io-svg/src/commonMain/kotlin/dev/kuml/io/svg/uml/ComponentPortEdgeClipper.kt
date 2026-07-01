package dev.kuml.io.svg.uml

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.ids.UmlIds

/**
 * Snappt die Endpunkte eines [dev.kuml.uml.UmlConnector]-Routings auf die
 * tatsächlichen Port-Quadrat-Positionen am Rand der beteiligten Komponenten
 * und baut einen orthogonalen Pfad, der die Ports senkrecht zur Seite
 * verlässt bzw. erreicht.
 *
 * **Hintergrund (V2.0.47).** Die ELK-Engine kennt keine UML-Komponenten-Ports
 * (siehe `// ELK does not know about ports yet`-Kommentar in
 * [UmlComponentSvg]). Connector-Edges werden deshalb von ELK an die obere
 * oder untere Bounding-Box-Kante einer Komponente geroutet — visuell landet
 * der Pfeil dann *neben* dem Port-Quadrat statt darauf, und im Vault-
 * Beispiel [[03 Bereiche/kUML/Beispiele/12 UML Component – Order Architecture]]
 * sah der Connector zwischen `OrderService::api` und
 * `InvoiceService::orderEvents` aus, als würde er die Komponentenmitte
 * treffen statt die beiden links angesetzten Ports.
 *
 * **Strategie.** Die Port-Verteilungsformel ist deterministisch und identisch
 * zu [UmlComponentSvg.renderPorts]: gerade Port-Indizes liegen links,
 * ungerade rechts, vertikal gleichmäßig verteilt. Dieser Clipper rekonstruiert
 * die Anchor-Position pro Port am äußeren Rand der jeweiligen
 * Komponenten-Box (Port-Quadrat 12 px breit, halb überlappend) und ersetzt
 * die ELK-Endpunkte plus alle Wegpunkte durch ein orthogonales Routing:
 *
 *  - **Beide Ports auf derselben Seite (LINKS↔LINKS oder RECHTS↔RECHTS)** →
 *    U-Form, die senkrecht aus den Ports heraus tritt und über einen
 *    gemeinsamen vertikalen Korridor außerhalb der Komponenten verläuft.
 *  - **Gegenüberliegende Seiten (LINKS↔RECHTS)** → Z-Form mit horizontalem
 *    Mittelstück.
 *
 * Erkennen die qualifizierte Endpunkt-Form `<componentId>::<portName>` nicht
 * oder findet sich der Port nicht in der Komponente, bleibt das
 * Original-Routing unverändert.
 */
internal object ComponentPortEdgeClipper {
    /**
     * Kantenlänge des Port-Quadrats in px. Muss synchron mit der
     * `portSize`-Konstanten in [UmlComponentSvg.renderPorts] bleiben, sonst
     * landen Connector-Endpunkte nicht exakt auf dem gezeichneten Quadrat.
     */
    private const val PORT_SIZE = 12f

    /**
     * Länge der senkrechten "Stub"-Strecke, mit der die Route den Port
     * verlässt. Reicht weit genug aus, damit der vertikale Korridor des
     * U-Routings deutlich außerhalb der Komponentenbox sitzt und sich nicht
     * mit den Port-Labels innen überschneidet.
     */
    private const val STUB_PX = 24f

    /**
     * Gesamte seitliche Ausladung einer geclippten Port-Route **über die
     * Komponenten-Box hinaus**: halbe Port-Breite (das Quadrat sitzt halb
     * überlappend auf dem Rand) plus die Stub-Länge des Korridors.
     *
     * [clip] läuft erst **nach** der Canvas-Größen-Berechnung
     * ([dev.kuml.layout.elk.ResultMapper] bzw. das `SvgDocument`-Padding) — die
     * so erzeugten Korridore ragen also bis zu diesem Betrag über die
     * Layout-Bounding-Box hinaus, von der weder das Canvas noch der
     * Diagrammrahmen etwas wissen. Der Renderer addiert diesen Wert deshalb
     * (plus einem kleinen Rahmen-Spalt) auf das Canvas-Padding von
     * Komponentendiagrammen, damit die Stubs nicht auf bzw. über den
     * Diagrammrahmen laufen. Analog zur vertikalen Korrektur
     * [UmlComponentContracts.TOTAL_UPWARD_EXTENT_PX] für ungebundene Contracts.
     */
    const val OUTWARD_EXTENT_PX: Float = PORT_SIZE / 2f + STUB_PX

    /**
     * Prüft, ob ein Connector mit diesen beiden Endpunkt-IDs von [clip] zu
     * einer Port-Route mit seitlichen Stubs umgebaut würde — also ob beide
     * Enden qualifizierte `componentId::portName`-Endpunkte sind und nicht
     * identisch (kein Self-Loop). Der Renderer nutzt das für die
     * Padding-Entscheidung, bevor [clip] tatsächlich läuft.
     */
    fun bindsPorts(
        end1Id: String,
        end2Id: String,
    ): Boolean = end1Id != end2Id && splitEndpoint(end1Id) != null && splitEndpoint(end2Id) != null

    private enum class Side { LEFT, RIGHT }

    private data class PortAnchor(
        val x: Float,
        val y: Float,
        val side: Side,
    )

    /**
     * Berechnet die absolute Andock-Position eines Ports am äußeren Rand
     * seiner Komponentenbox — exakt nach derselben Formel wie
     * [UmlComponentSvg.renderPorts]:
     *
     *  - Gerade Index → linke Seite, ungerade Index → rechte Seite.
     *  - Vertikale Position innerhalb der Seite: `i * h / (n + 1)` —
     *    gleichmäßige Verteilung mit `n + 1` Lücken.
     *  - Das Port-Quadrat sitzt halb überlappend auf dem Rand; seine
     *    äußere Kante ist `±PORT_SIZE / 2` neben der Komponentenbox.
     */
    private fun portAnchor(
        component: UmlComponent,
        compBounds: Rect,
        portName: String,
    ): PortAnchor? {
        val portIndex = component.ports.indexOfFirst { it.name == portName }
        if (portIndex < 0) return null
        val onLeft = (portIndex % 2) == 0
        val sideList =
            component.ports.filterIndexed { idx, _ ->
                (idx % 2 == 0) == onLeft
            }
        val withinSideIdx = sideList.indexOfFirst { it.name == portName }
        if (withinSideIdx < 0) return null

        val bx = compBounds.origin.x
        val by = compBounds.origin.y
        val w = compBounds.size.width
        val h = compBounds.size.height
        val py = by + h * (withinSideIdx + 1) / (sideList.size + 1)
        return if (onLeft) {
            PortAnchor(x = bx - PORT_SIZE / 2f, y = py, side = Side.LEFT)
        } else {
            PortAnchor(x = bx + w + PORT_SIZE / 2f, y = py, side = Side.RIGHT)
        }
    }

    /**
     * Teilt eine qualifizierte Endpunkt-ID `"<componentId>::<portName>"` in
     * (componentId, portName). Liefert `null` wenn die Form nicht passt
     * (z.B. Connector zwischen Parts statt Ports).
     */
    private fun splitEndpoint(endpointId: String): Pair<String, String>? {
        val sep = endpointId.lastIndexOf(UmlIds.SEP)
        if (sep <= 0) return null
        val compId = endpointId.substring(0, sep)
        val portName = endpointId.substring(sep + UmlIds.SEP.length)
        if (portName.isEmpty()) return null
        return compId to portName
    }

    /**
     * Baut eine orthogonale Route zwischen zwei [PortAnchor]s. Die Form
     * hängt von den Port-Seiten ab — siehe Klassen-KDoc.
     *
     * Für die **Z-Form** (gegenüberliegende Seiten) wird der Korridor
     * obstacle-aware gewählt: liegen die beiden Komponentenboxen vertikal
     * getrennt (eine über der anderen) und überlappen sich horizontal, läuft
     * der Mittelkorridor **horizontal durch die vertikale Lücke** zwischen den
     * Boxen statt vertikal — sonst stürzt die senkrechte Mittelstrecke mitten
     * durch die Zielbox (Vault-Beispiel [[35 AUTOSAR Classic – SW-Komponenten]]:
     * `BrakeControllerSwc::DiagOut → DiagActuatorSwc::DiagIn`). In allen anderen
     * Fällen (Boxen nebeneinander) bleibt der vertikale Mittelkorridor.
     */
    private fun buildOrthogonalRoute(
        src: PortAnchor,
        tgt: PortAnchor,
        srcBounds: Rect,
        tgtBounds: Rect,
    ): EdgeRoute {
        val sStub = if (src.side == Side.LEFT) -STUB_PX else STUB_PX
        val tStub = if (tgt.side == Side.LEFT) -STUB_PX else STUB_PX
        val sx = src.x + sStub
        val tx = tgt.x + tStub

        val waypoints = mutableListOf<Point>()
        waypoints.add(Point(sx, src.y))
        if (src.side == tgt.side) {
            // U-Form: gemeinsamer vertikaler Korridor außerhalb beider
            // Komponenten. Auf der linken Seite ist das die WEITER LINKS
            // gelegene der beiden Stub-Positionen, rechts entsprechend
            // die weiter rechts gelegene.
            val cornerX =
                if (src.side == Side.LEFT) {
                    minOf(sx, tx)
                } else {
                    maxOf(sx, tx)
                }
            waypoints.add(Point(cornerX, src.y))
            waypoints.add(Point(cornerX, tgt.y))
            waypoints.add(Point(tx, tgt.y))
        } else {
            val sLeft = srcBounds.origin.x
            val sRight = srcBounds.origin.x + srcBounds.size.width
            val sTop = srcBounds.origin.y
            val sBottom = srcBounds.origin.y + srcBounds.size.height
            val tLeft = tgtBounds.origin.x
            val tRight = tgtBounds.origin.x + tgtBounds.size.width
            val tTop = tgtBounds.origin.y
            val tBottom = tgtBounds.origin.y + tgtBounds.size.height

            val horizontallySeparated = sRight < tLeft || tRight < sLeft
            val verticallySeparated = sBottom < tTop || tBottom < sTop

            if (!horizontallySeparated && verticallySeparated) {
                // Z-Form mit HORIZONTALEM Mittelkorridor durch die vertikale
                // Lücke zwischen den Boxen — vermeidet das Durchstoßen der
                // Zielbox, wenn die Boxen übereinander statt nebeneinander
                // liegen.
                val corridorY =
                    if (sBottom <= tTop) {
                        (sBottom + tTop) / 2f
                    } else {
                        (tBottom + sTop) / 2f
                    }
                waypoints.add(Point(sx, corridorY))
                waypoints.add(Point(tx, corridorY))
                waypoints.add(Point(tx, tgt.y))
            } else {
                // Z-Form: vertikaler Mittelkorridor zwischen den beiden Stub-x
                // (Boxen liegen nebeneinander).
                val midX = (sx + tx) / 2f
                waypoints.add(Point(midX, src.y))
                waypoints.add(Point(midX, tgt.y))
                waypoints.add(Point(tx, tgt.y))
            }
        }
        return EdgeRoute.OrthogonalRounded(
            source = Point(src.x, src.y),
            target = Point(tgt.x, tgt.y),
            waypoints = waypoints,
            cornerRadiusPx = 0f,
        )
    }

    /**
     * Snappt die Endpunkte des [route] auf die Port-Quadrate der beiden
     * referenzierten Komponenten, sofern beide Enden qualifizierte
     * `componentId::portName`-Endpunkte sind. Andernfalls bleibt das
     * Original-Routing unverändert.
     *
     * @param end1Id qualifizierte ID des ersten Connector-Endpunkts.
     * @param end2Id qualifizierte ID des zweiten Connector-Endpunkts.
     * @param componentLookup liefert die [UmlComponent] zu einer ID
     *        (typischerweise eine `flatElementIndex`-Map des Renderers).
     * @param boundsLookup liefert die in Canvas-Koordinaten verschobene
     *        Bounding-Box einer Komponente — die gleiche [Rect], die der
     *        NodeRenderer für diese Komponente zeichnet (inkl. Padding).
     */
    fun clip(
        route: EdgeRoute,
        end1Id: String,
        end2Id: String,
        componentLookup: (String) -> UmlComponent?,
        boundsLookup: (String) -> Rect?,
    ): EdgeRoute {
        val (s1Id, s1Port) = splitEndpoint(end1Id) ?: return route
        val (s2Id, s2Port) = splitEndpoint(end2Id) ?: return route
        val srcComp = componentLookup(s1Id) ?: return route
        val tgtComp = componentLookup(s2Id) ?: return route
        val srcBounds = boundsLookup(s1Id) ?: return route
        val tgtBounds = boundsLookup(s2Id) ?: return route
        val srcAnchor = portAnchor(srcComp, srcBounds, s1Port) ?: return route
        val tgtAnchor = portAnchor(tgtComp, tgtBounds, s2Port) ?: return route
        return buildOrthogonalRoute(srcAnchor, tgtAnchor, srcBounds, tgtBounds)
    }
}
