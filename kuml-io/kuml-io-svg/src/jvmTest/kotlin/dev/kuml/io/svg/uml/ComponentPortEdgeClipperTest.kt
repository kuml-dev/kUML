package dev.kuml.io.svg.uml

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * V2.0.47 — Regressionsschutz für den
 * [[03 Bereiche/kUML/Beispiele/12 UML Component – Order Architecture]]-Fall.
 *
 * Vor dem Fix routete ELK die Connector-Edge zwischen `OrderService::api` und
 * `InvoiceService::orderEvents` an die Bounding-Box-Kante der Komponenten —
 * die Endpunkte landeten *neben* den Port-Quadraten statt darauf. Der
 * [ComponentPortEdgeClipper] snappt nun beide Endpunkte auf die exakte Port-
 * Position (gleiche Formel wie `UmlComponentSvg.renderPorts`) und konstruiert
 * ein U-förmiges orthogonales Routing, das beide Ports senkrecht zur Seite
 * verlässt.
 */
class ComponentPortEdgeClipperTest :
    FunSpec({

        // Port-Quadrat-Konstanten — müssen mit ComponentPortEdgeClipper / UmlComponentSvg synchron bleiben.
        val portSize = 12f
        val stub = 24f

        test("snaps left-port endpoints to outer port edge and routes U-shape on the left") {
            // OrderService oben bei (200, 50), 200×100 Box → Port `api` auf
            // linker Seite, vertikal in der Mitte (1 Port → 50% der Höhe).
            val orderService =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports = listOf(UmlPort(id = "OrderService::api", name = "api")),
                )
            // InvoiceService unten bei (220, 280), 240×110 → Port `orderEvents`
            // ebenfalls links, vertikal in der Mitte.
            val invoiceService =
                UmlComponent(
                    id = "InvoiceService",
                    name = "InvoiceService",
                    ports = listOf(UmlPort(id = "InvoiceService::orderEvents", name = "orderEvents")),
                )
            val srcBounds = Rect(Point(200f, 50f), Size(200f, 100f))
            val tgtBounds = Rect(Point(220f, 280f), Size(240f, 110f))

            // Original-Route der "Engine" — irgendwas Sinnloses, das vom
            // Clipper verworfen werden muss.
            val original: EdgeRoute =
                EdgeRoute.OrthogonalRounded(
                    source = Point(300f, 150f),
                    target = Point(340f, 280f),
                    waypoints = listOf(Point(300f, 200f), Point(340f, 200f)),
                    cornerRadiusPx = 0f,
                )

            val clipped =
                ComponentPortEdgeClipper.clip(
                    route = original,
                    end1Id = "OrderService::api",
                    end2Id = "InvoiceService::orderEvents",
                    componentLookup = { id ->
                        when (id) {
                            "OrderService" -> orderService
                            "InvoiceService" -> invoiceService
                            else -> null
                        }
                    },
                    boundsLookup = { id ->
                        when (id) {
                            "OrderService" -> srcBounds
                            "InvoiceService" -> tgtBounds
                            else -> null
                        }
                    },
                )

            // Beide Ports sind auf der LINKEN Seite ihrer Komponente. Erwartet:
            // U-Form, deren Endpunkte exakt auf der äußeren Port-Quadrat-Kante
            // sitzen (= Komponentenkante - portSize/2).
            val srcExpectedX = srcBounds.origin.x - portSize / 2f // 194
            val srcExpectedY = srcBounds.origin.y + srcBounds.size.height / 2f // 100
            val tgtExpectedX = tgtBounds.origin.x - portSize / 2f // 214
            val tgtExpectedY = tgtBounds.origin.y + tgtBounds.size.height / 2f // 335

            val rounded = clipped.shouldBeInstanceOf<EdgeRoute.OrthogonalRounded>()
            rounded.source.x shouldBe (srcExpectedX plusOrMinus 0.01f)
            rounded.source.y shouldBe (srcExpectedY plusOrMinus 0.01f)
            rounded.target.x shouldBe (tgtExpectedX plusOrMinus 0.01f)
            rounded.target.y shouldBe (tgtExpectedY plusOrMinus 0.01f)

            // Erster Wegpunkt: senkrecht aus dem linken Port heraus (also nach
            // links — x ist kleiner als srcExpectedX um den Stub-Wert).
            rounded.waypoints.first().x shouldBe ((srcExpectedX - stub) plusOrMinus 0.01f)
            rounded.waypoints.first().y shouldBe (srcExpectedY plusOrMinus 0.01f)

            // Mittlerer vertikaler Korridor: min beider Stub-x (also der weiter
            // links liegende), damit beide Ports erreicht werden ohne
            // Komponenten zu kreuzen.
            val srcStubX = srcExpectedX - stub
            val tgtStubX = tgtExpectedX - stub
            val cornerX = minOf(srcStubX, tgtStubX)
            rounded.waypoints[1].x shouldBe (cornerX plusOrMinus 0.01f)
            rounded.waypoints[1].y shouldBe (srcExpectedY plusOrMinus 0.01f)
            rounded.waypoints[2].x shouldBe (cornerX plusOrMinus 0.01f)
            rounded.waypoints[2].y shouldBe (tgtExpectedY plusOrMinus 0.01f)
            rounded.waypoints[3].x shouldBe (tgtStubX plusOrMinus 0.01f)
            rounded.waypoints[3].y shouldBe (tgtExpectedY plusOrMinus 0.01f)
        }

        test("snaps opposite-side ports (left↔right) to a Z-shape") {
            // Komponente A links, Port api rechts (index 1 → odd → right side).
            val compA =
                UmlComponent(
                    id = "A",
                    name = "A",
                    ports =
                        listOf(
                            UmlPort(id = "A::filler", name = "filler"), // index 0 → left
                            UmlPort(id = "A::api", name = "api"), // index 1 → right
                        ),
                )
            // Komponente B rechts, Port consumer links (index 0 → left).
            val compB =
                UmlComponent(
                    id = "B",
                    name = "B",
                    ports = listOf(UmlPort(id = "B::consumer", name = "consumer")),
                )
            val aBounds = Rect(Point(0f, 50f), Size(100f, 80f))
            val bBounds = Rect(Point(300f, 50f), Size(100f, 80f))

            val clipped =
                ComponentPortEdgeClipper.clip(
                    route = EdgeRoute.Direct(Point(0f, 0f), Point(0f, 0f)),
                    end1Id = "A::api",
                    end2Id = "B::consumer",
                    componentLookup = { id ->
                        when (id) {
                            "A" -> compA
                            "B" -> compB
                            else -> null
                        }
                    },
                    boundsLookup = { id ->
                        when (id) {
                            "A" -> aBounds
                            "B" -> bBounds
                            else -> null
                        }
                    },
                )

            val rounded = clipped.shouldBeInstanceOf<EdgeRoute.OrthogonalRounded>()
            // A::api liegt auf der rechten Seite von A → äußere Kante bei x = 100 + 6 = 106.
            rounded.source.x shouldBe (106f plusOrMinus 0.01f)
            // B::consumer liegt auf der linken Seite von B → äußere Kante bei x = 300 - 6 = 294.
            rounded.target.x shouldBe (294f plusOrMinus 0.01f)
            // Z-Form: horizontale Mittelstrecke bei (srcStubX + tgtStubX) / 2.
            val srcStubX = 106f + stub // right → +stub
            val tgtStubX = 294f - stub // left → -stub
            val midX = (srcStubX + tgtStubX) / 2f
            rounded.waypoints[1].x shouldBe (midX plusOrMinus 0.01f)
            rounded.waypoints[2].x shouldBe (midX plusOrMinus 0.01f)
        }

        test("routes edges around intervening sibling components (Plugin-API topology)") {
            // Reproduziert [[03 Bereiche/kUML/Beispiele/38 UML Component – Plugin API]]
            // (Präsentations-Quelle `35_UML_Component_Plugin_API.kuml.kts`):
            // `kUML Core` oben mit drei Ports (`theme`, `renderer`, `codegen`),
            // drei Geschwister-Komponenten nebeneinander darunter (`PdV Theme
            // Plugin`, `TypeScript Codegen Plugin`, `PDF Renderer Plugin`), je
            // mit einem `spi`-Port. Vor dem Fix liefen zwei Kanten quer durch
            // eine dazwischenliegende Sibling-Box:
            //  - `kUML Core::theme → PdV Theme Plugin::spi` durch
            //    `TypeScript Codegen Plugin` (U-Form-Fall, gleiche Portseite).
            //  - `kUML Core::renderer → PDF Renderer Plugin::spi` durch
            //    `PdV Theme Plugin` (Z-Form-Fall, gegenüberliegende Portseiten,
            //    Boxen nebeneinander in derselben Zeile).
            //
            // Die Bounding-Boxen unten sind 1:1 aus einem echten
            // `kuml render`-Lauf dieses Beispiels übernommen (SVG-`<g
            // transform="translate(...)">`- und `<rect width/height>`-Werte),
            // damit der Test die reale ELK-Layout-Geometrie trifft und nicht
            // nur eine synthetische Konstellation.
            val core =
                UmlComponent(
                    id = "kUML Core",
                    name = "kUML Core",
                    ports =
                        listOf(
                            UmlPort(id = "kUML Core::theme", name = "theme"), // index 0 → left
                            UmlPort(id = "kUML Core::renderer", name = "renderer"), // index 1 → right
                            UmlPort(id = "kUML Core::codegen", name = "codegen"), // index 2 → left (2nd on side)
                        ),
                )
            val pdvTheme =
                UmlComponent(
                    id = "PdV Theme Plugin",
                    name = "PdV Theme Plugin",
                    ports = listOf(UmlPort(id = "PdV Theme Plugin::spi", name = "spi")),
                )
            val tsCodegen =
                UmlComponent(
                    id = "TypeScript Codegen Plugin",
                    name = "TypeScript Codegen Plugin",
                    ports = listOf(UmlPort(id = "TypeScript Codegen Plugin::spi", name = "spi")),
                )
            val pdfRenderer =
                UmlComponent(
                    id = "PDF Renderer Plugin",
                    name = "PDF Renderer Plugin",
                    ports = listOf(UmlPort(id = "PDF Renderer Plugin::spi", name = "spi")),
                )

            val coreBounds = Rect(Point(155.79f, 56f), Size(306f, 80f))
            val pdvThemeBounds = Rect(Point(404f, 236f), Size(214f, 80f))
            val tsCodegenBounds = Rect(Point(56f, 236f), Size(287f, 80f))
            val pdfRendererBounds = Rect(Point(679f, 236f), Size(237f, 80f))

            val original: EdgeRoute = EdgeRoute.Direct(Point(0f, 0f), Point(0f, 0f))

            val componentLookup: (String) -> UmlComponent? = { id ->
                when (id) {
                    "kUML Core" -> core
                    "PdV Theme Plugin" -> pdvTheme
                    "TypeScript Codegen Plugin" -> tsCodegen
                    "PDF Renderer Plugin" -> pdfRenderer
                    else -> null
                }
            }
            val boundsLookup: (String) -> Rect? = { id ->
                when (id) {
                    "kUML Core" -> coreBounds
                    "PdV Theme Plugin" -> pdvThemeBounds
                    "TypeScript Codegen Plugin" -> tsCodegenBounds
                    "PDF Renderer Plugin" -> pdfRendererBounds
                    else -> null
                }
            }
            val allBounds = listOf(coreBounds, pdvThemeBounds, tsCodegenBounds, pdfRendererBounds)

            val clippedTheme =
                ComponentPortEdgeClipper.clip(
                    route = original,
                    end1Id = "kUML Core::theme",
                    end2Id = "PdV Theme Plugin::spi",
                    componentLookup = componentLookup,
                    boundsLookup = boundsLookup,
                    siblingBounds = allBounds,
                )
            val roundedTheme = clippedTheme.shouldBeInstanceOf<EdgeRoute.OrthogonalRounded>()
            val pathTheme = listOf(roundedTheme.source) + roundedTheme.waypoints + roundedTheme.target
            for (box in allBounds) {
                for (i in 0 until pathTheme.size - 1) {
                    segmentIntersectsRectInterior(pathTheme[i], pathTheme[i + 1], box) shouldBe false
                }
            }

            val clippedRenderer =
                ComponentPortEdgeClipper.clip(
                    route = original,
                    end1Id = "kUML Core::renderer",
                    end2Id = "PDF Renderer Plugin::spi",
                    componentLookup = componentLookup,
                    boundsLookup = boundsLookup,
                    siblingBounds = allBounds,
                )
            val roundedRenderer = clippedRenderer.shouldBeInstanceOf<EdgeRoute.OrthogonalRounded>()
            val pathRenderer = listOf(roundedRenderer.source) + roundedRenderer.waypoints + roundedRenderer.target
            for (box in allBounds) {
                for (i in 0 until pathRenderer.size - 1) {
                    segmentIntersectsRectInterior(pathRenderer[i], pathRenderer[i + 1], box) shouldBe false
                }
            }
        }

        test("leaves route untouched for non-port endpoint ids") {
            val comp = UmlComponent(id = "C", name = "C")
            val original = EdgeRoute.Direct(Point(1f, 2f), Point(3f, 4f))
            val clipped =
                ComponentPortEdgeClipper.clip(
                    route = original,
                    end1Id = "C", // not qualified
                    end2Id = "D",
                    componentLookup = { _ -> comp },
                    boundsLookup = { _ -> Rect(Point(0f, 0f), Size(10f, 10f)) },
                )
            clipped shouldBe original
        }

        test("leaves route untouched if port name not declared on component") {
            val comp = UmlComponent(id = "C", name = "C") // no ports
            val original = EdgeRoute.Direct(Point(1f, 2f), Point(3f, 4f))
            val clipped =
                ComponentPortEdgeClipper.clip(
                    route = original,
                    end1Id = "C::nope",
                    end2Id = "C::also-nope",
                    componentLookup = { _ -> comp },
                    boundsLookup = { _ -> Rect(Point(0f, 0f), Size(10f, 10f)) },
                )
            clipped shouldBe original
        }
    })

/**
 * Schnitt-Test für ein achsenparalleles Segment gegen das **Innere** eines
 * Rechtecks — analog zum Liang-Barsky-Muster aus `KumlSvgRendererTest`. Nur
 * ein echtes Durchqueren der Fläche zählt als Treffer; Berührung am Rand
 * (z.B. weil eine Route exakt an einer Box-Kante entlangläuft) zählt nicht.
 */
private fun segmentIntersectsRectInterior(
    a: Point,
    b: Point,
    r: Rect,
): Boolean {
    val left = r.origin.x
    val right = r.origin.x + r.size.width
    val top = r.origin.y
    val bottom = r.origin.y + r.size.height
    val eps = 0.01f
    return if (a.x == b.x) {
        // Vertikales Segment.
        val x = a.x
        val y1 = minOf(a.y, b.y)
        val y2 = maxOf(a.y, b.y)
        x > left + eps && x < right - eps && maxOf(y1, top) < minOf(y2, bottom) - eps
    } else if (a.y == b.y) {
        // Horizontales Segment.
        val y = a.y
        val x1 = minOf(a.x, b.x)
        val x2 = maxOf(a.x, b.x)
        y > top + eps && y < bottom - eps && maxOf(x1, left) < minOf(x2, right) - eps
    } else {
        // Diagonales Segment sollte in dieser Routing-Klasse nicht vorkommen
        // (alle Routen sind achsenparallel) — konservativ als "kein Treffer"
        // behandeln, statt eine generische Liang-Barsky-Implementierung zu
        // duplizieren, die hier nicht gebraucht wird.
        false
    }
}
