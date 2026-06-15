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
