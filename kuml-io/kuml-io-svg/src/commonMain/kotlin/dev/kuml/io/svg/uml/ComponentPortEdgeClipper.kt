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
     * `true` when the axis-aligned segment `a→b` (horizontal or vertical —
     * every segment in this clipper's routes is one or the other) passes
     * through the **interior** of [box]. Touching an edge/corner does not
     * count.
     */
    private fun axisSegmentCrossesBox(
        a: Point,
        b: Point,
        box: Rect,
    ): Boolean {
        val boxLeft = box.origin.x
        val boxRight = box.origin.x + box.size.width
        val boxTop = box.origin.y
        val boxBottom = box.origin.y + box.size.height
        return if (a.x == b.x) {
            val x = a.x
            val y0 = minOf(a.y, b.y)
            val y1 = maxOf(a.y, b.y)
            x > boxLeft && x < boxRight && maxOf(y0, boxTop) < minOf(y1, boxBottom)
        } else {
            val y = a.y
            val x0 = minOf(a.x, b.x)
            val x1 = maxOf(a.x, b.x)
            y > boxTop && y < boxBottom && maxOf(x0, boxLeft) < minOf(x1, boxRight)
        }
    }

    /**
     * Detour-Gasse-Abstand um eine Hindernis-Box: groß genug, um sicher
     * außerhalb der Box zu liegen, ohne unnötig weit auszuholen.
     */
    private const val OBSTACLE_GUTTER_PX = 12f

    /**
     * `true` wenn irgendein Segment der Wegpunkt-Kette `points` (inklusive
     * Quell- und Zielpunkt) das Innere von [box] durchquert.
     */
    private fun pathCrossesBox(
        points: List<Point>,
        box: Rect,
    ): Boolean {
        for (i in 0 until points.size - 1) {
            if (axisSegmentCrossesBox(points[i], points[i + 1], box)) return true
        }
        return false
    }

    /**
     * Läuft die fertig gebaute Wegpunkt-Kette einer Port-Route (`points`,
     * inklusive Quell- und Zielpunkt) ab und ersetzt sie — solange irgendein
     * Segment eine der [siblingBounds] (außer [srcBounds]/[tgtBounds] selbst)
     * durchquert — durch eine Route, die um genau diese Box **vollständig**
     * herumführt.
     *
     * Generalisiert die U-Form-/Z-Form-Korridorwahl in [buildOrthogonalRoute]:
     * die Korridor-Positionierung dort vermeidet die *typischen* Kollisionen
     * (Korridor fällt in eine Box), deckt aber nicht jede Konstellation ab —
     * insbesondere nicht das horizontale Einlaufsegment auf Höhe des
     * Zielports, das bei drei nebeneinanderliegenden Komponenten mit
     * identischer Port-Höhe mitten durch die mittlere Box laufen kann
     * (Vault-Beispiel [[35 UML Component – Plugin API]]:
     * `kUML Core::theme → PdV Theme Plugin::spi` — das letzte horizontale
     * Segment auf `y = tgt.y` kreuzte die dazwischenliegende
     * `TypeScript Codegen Plugin`-Box, obwohl der vertikale Korridor bereits
     * links daran vorbeigeschoben war). Dieser generische Nachbearbeitungs-
     * schritt fängt genau solche Restfälle ab, unabhängig davon welche
     * Korridor-Form vorher gewählt wurde.
     *
     * **Wichtige Invariante, die die vorherige (fehlerhafte) Fassung verletzt
     * hat**: ein lokaler "Huckel"-Umweg um ein einzelnes Segment funktioniert
     * nur, wenn weder `a` noch `b` selbst auf der blockierten Achse liegen.
     * Bei einer U-Form-Route liegen aber BEIDE Korridor-Wegpunkte
     * (`(cornerX, src.y)` und `(cornerX, tgt.y)`) auf derselben `x` — ein
     * Huckel, der zu `b` auf genau dieser `x` zurückkehrt, kreuzt die Box
     * erneut, sobald `b.y` selbst in der Box-Höhenspanne liegt (endet nie,
     * bis zum Iterationslimit). Der Fix ersetzt deshalb **den gesamten
     * Streckenabschnitt zwischen dem letzten unkritischen Punkt vor der Box
     * und dem ersten unkritischen Punkt danach** durch einen Umweg über eine
     * der vier Box-Ecken, der komplett außerhalb der Box liegt und exakt bei
     * den (unveränderten) Nachbarpunkten andockt.
     */
    private fun avoidObstacles(
        points: List<Point>,
        siblingBounds: List<Rect>,
        srcBounds: Rect,
        tgtBounds: Rect,
    ): List<Point> {
        if (siblingBounds.isEmpty()) return points
        val obstacles = siblingBounds.filter { it != srcBounds && it != tgtBounds }
        if (obstacles.isEmpty()) return points

        var result = points
        // Deckelt die Gesamtzahl der Umweg-Einfügungen — mehr als ein paar
        // dutzend dazwischenliegende Geschwister-Komponenten sind praktisch
        // nicht zu erwarten; verhindert eine Endlosschleife bei
        // pathologischen/degenerierten Layouts.
        repeat(obstacles.size + 8) {
            val box = obstacles.firstOrNull { pathCrossesBox(result, it) } ?: return result
            val boxLeft = box.origin.x
            val boxRight = box.origin.x + box.size.width
            val boxTop = box.origin.y
            val boxBottom = box.origin.y + box.size.height
            val leftGutter = boxLeft - OBSTACLE_GUTTER_PX
            val rightGutter = boxRight + OBSTACLE_GUTTER_PX
            val topGutter = boxTop - OBSTACLE_GUTTER_PX
            val bottomGutter = boxBottom + OBSTACLE_GUTTER_PX

            // Umweg über die Box-Ecke, die am nächsten an der geraden Linie
            // vom ersten zum letzten Punkt der Route liegt — probiert alle
            // vier Ecken der Reihe nach (nächste zuerst) und übernimmt die
            // erste, deren kompletter Ersatzabschnitt (Start → Ecke-Umweg →
            // Ende) KEINE der `obstacles` mehr kreuzt. Fällt keine Ecke
            // kollisionsfrei aus, wird die naheliegendste trotzdem
            // übernommen (nie schlechter als vorher, da mindestens diese eine
            // Box umgangen wird) — die äußere `repeat`-Schleife behandelt
            // etwaige verbleibende Kollisionen in der nächsten Runde.
            val first = result.first()
            val last = result.last()
            val corners =
                listOf(
                    Point(leftGutter, topGutter),
                    Point(rightGutter, topGutter),
                    Point(leftGutter, bottomGutter),
                    Point(rightGutter, bottomGutter),
                ).sortedBy { corner ->
                    distanceToSegment(corner, first, last)
                }

            // Der Ersatz-Abschnitt darf weder eine der `obstacles` noch die
            // Quell-/Ziel-Box selbst erneut kreuzen — sonst kann der Umweg
            // rückwärts durch die eigene Quellkomponente laufen, bevor er
            // nach außen zur Ziel-Ecke abbiegt (beobachtet beim same-row
            // Fall: der Umweg um `TypeScript Codegen Plugin` wählte zunächst
            // eine Ecke rechts davon, der Weg dorthin führte aber mitten
            // durch `kUML Core` selbst, weil der Startpunkt links von Core
            // lag). `srcBounds`/`tgtBounds` sind hier reine
            // Validierungs-Hindernisse — sie werden nicht selbst umfahren
            // (das würde bei anliegenden Ports keinen Sinn ergeben), aber ein
            // Kandidat, der durch sie hindurchläuft, wird verworfen.
            val validationBoxes = obstacles + srcBounds + tgtBounds
            var replacement: List<Point>? = null
            for (corner in corners) {
                // Rechtwinkliger Umweg: Start → (corner.x auf Start-Y ODER
                // Start-X auf corner.y, je nachdem was näher ist) → Ecke →
                // (symmetrisch für Ende) → Ende. Um die Konstruktion einfach
                // und immer achsenparallel zu halten, wird ein L-Zug über die
                // Ecke selbst verwendet: Start → (corner.x, first.y) →
                // (corner.x, corner.y) → (last.x, corner.y) → Ende.
                val candidate =
                    listOf(
                        first,
                        Point(corner.x, first.y),
                        Point(corner.x, corner.y),
                        Point(last.x, corner.y),
                        last,
                    )
                if (validationBoxes.none { pathCrossesBox(candidate, it) }) {
                    replacement = candidate
                    break
                }
                if (replacement == null || obstacles.none { pathCrossesBox(candidate, it) }) {
                    // Fallback-Präferenz: ein Kandidat, der wenigstens keine
                    // `obstacles` mehr kreuzt (auch wenn er src/tgt streift),
                    // ist besser als gar keiner.
                    if (replacement == null) replacement = candidate
                }
            }
            result = replacement ?: result
        }
        return result
    }

    /** Kürzester Abstand von [p] zur Strecke `a→b` (nicht zur unendlichen Gerade). */
    private fun distanceToSegment(
        p: Point,
        a: Point,
        b: Point,
    ): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0f) {
            val ddx = p.x - a.x
            val ddy = p.y - a.y
            return kotlin.math.sqrt(ddx * ddx + ddy * ddy)
        }
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSq).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        val ddx = p.x - projX
        val ddy = p.y - projY
        return kotlin.math.sqrt(ddx * ddx + ddy * ddy)
    }

    /**
     * Baut eine orthogonale Route zwischen zwei [PortAnchor]s. Die Form
     * hängt von den Port-Seiten ab — siehe Klassen-KDoc.
     *
     *  - **U-Form** (gleiche Seite): gemeinsamer vertikaler Korridor außerhalb
     *    beider Komponenten.
     *  - **Z-Form** (gegenüberliegende Seiten): liegen die beiden
     *    Komponentenboxen vertikal getrennt (eine über der anderen) und
     *    überlappen sich horizontal, läuft der Mittelkorridor **horizontal
     *    durch die vertikale Lücke** zwischen den Boxen statt vertikal —
     *    sonst stürzt die senkrechte Mittelstrecke mitten durch die Zielbox
     *    (Vault-Beispiel [[35 AUTOSAR Classic – SW-Komponenten]]:
     *    `BrakeControllerSwc::DiagOut → DiagActuatorSwc::DiagIn`). Liegen die
     *    Boxen nebeneinander, bleibt der vertikale Mittelkorridor.
     *
     * Nach der Grundform-Konstruktion läuft [avoidObstacles] als generischer
     * Nachbearbeitungsschritt über die komplette Wegpunkt-Kette und fügt für
     * jedes Segment, das eine dritte, dazwischenliegende Geschwister-
     * Komponente aus [siblingBounds] durchquert, einen rechteckigen Umweg um
     * diese Box ein (Vault-Beispiel [[35 UML Component – Plugin API]]:
     * `kUML Core::theme → PdV Theme Plugin::spi` bzw.
     * `kUML Core::renderer → PDF Renderer Plugin::spi` liefen ohne diesen
     * Schritt durch die dazwischenliegende `TypeScript Codegen Plugin`- bzw.
     * `PdV Theme Plugin`-Box).
     */
    private fun buildOrthogonalRoute(
        src: PortAnchor,
        tgt: PortAnchor,
        srcBounds: Rect,
        tgtBounds: Rect,
        siblingBounds: List<Rect> = emptyList(),
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

        val fullPath =
            avoidObstacles(
                points = listOf(Point(src.x, src.y)) + waypoints + Point(tgt.x, tgt.y),
                siblingBounds = siblingBounds,
                srcBounds = srcBounds,
                tgtBounds = tgtBounds,
            )
        return EdgeRoute.OrthogonalRounded(
            source = fullPath.first(),
            target = fullPath.last(),
            waypoints = fullPath.subList(1, fullPath.size - 1),
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
     * @param siblingBounds Bounding-Boxen aller anderen sichtbaren
     *        Komponenten im Diagramm (ohne Quelle/Ziel), zur Hindernis-
     *        Erkennung beim same-row Z-Form-Korridor — siehe
     *        [buildOrthogonalRoute]-KDoc. Optional; leer bedeutet "keine
     *        Hindernis-Prüfung" (Verhalten vor diesem Parameter unverändert).
     */
    fun clip(
        route: EdgeRoute,
        end1Id: String,
        end2Id: String,
        componentLookup: (String) -> UmlComponent?,
        boundsLookup: (String) -> Rect?,
        siblingBounds: List<Rect> = emptyList(),
    ): EdgeRoute {
        val (s1Id, s1Port) = splitEndpoint(end1Id) ?: return route
        val (s2Id, s2Port) = splitEndpoint(end2Id) ?: return route
        val srcComp = componentLookup(s1Id) ?: return route
        val tgtComp = componentLookup(s2Id) ?: return route
        val srcBounds = boundsLookup(s1Id) ?: return route
        val tgtBounds = boundsLookup(s2Id) ?: return route
        val srcAnchor = portAnchor(srcComp, srcBounds, s1Port) ?: return route
        val tgtAnchor = portAnchor(tgtComp, tgtBounds, s2Port) ?: return route
        return buildOrthogonalRoute(srcAnchor, tgtAnchor, srcBounds, tgtBounds, siblingBounds)
    }
}
