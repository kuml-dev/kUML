package dev.kuml.io.svg

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlGeneralization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Strukturelle Tests für [SelfLoopRouter].
 *
 * Sicherstellt, dass:
 * 1. Self-Loop-Erkennung über die richtigen Endpunkt-IDs je Relationship-Typ läuft
 *    (Association: `ends[0].typeId == ends[1].typeId`, Generalization:
 *    `specificId == generalId`).
 * 2. Die generierte Route eine C-Schleife auf der rechten Seite der Knotenbox
 *    bildet (zwei Wegpunkte, beide rechts neben der Box, vertikal versetzt).
 * 3. Nicht-Self-Loop-Kanten unverändert durchgereicht werden.
 */
class SelfLoopRouterTest :
    FunSpec({

        val nodeLayout =
            NodeLayout(
                bounds = Rect(origin = Point(x = 100f, y = 50f), size = Size(width = 200f, height = 80f)),
            )

        test("UmlAssociation mit identischen Endpunkt-Typ-IDs ist ein Self-Loop") {
            val selfAssoc =
                UmlAssociation(
                    id = "assoc1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Node"),
                            UmlAssociationEnd(typeId = "Node"),
                        ),
                )
            SelfLoopRouter.isSelfLoop(selfAssoc) shouldBe true
            SelfLoopRouter.selfLoopNodeId(selfAssoc) shouldBe "Node"
        }

        test("UmlAssociation mit unterschiedlichen Typ-IDs ist kein Self-Loop") {
            val regularAssoc =
                UmlAssociation(
                    id = "assoc2",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "NodeA"),
                            UmlAssociationEnd(typeId = "NodeB"),
                        ),
                )
            SelfLoopRouter.isSelfLoop(regularAssoc) shouldBe false
            SelfLoopRouter.selfLoopNodeId(regularAssoc).shouldBeNull()
        }

        test("UmlGeneralization mit specificId == generalId ist ein Self-Loop") {
            val selfGen = UmlGeneralization(id = "gen1", specificId = "Node", generalId = "Node")
            SelfLoopRouter.isSelfLoop(selfGen) shouldBe true
            SelfLoopRouter.selfLoopNodeId(selfGen) shouldBe "Node"
        }

        test("selfLoopRoute liefert C-Schleife rechts neben der Box") {
            val route = SelfLoopRouter.selfLoopRoute(nodeLayout)

            // Bounding box: x=100..300, y=50..130
            val rightEdge = 300f
            route.source.x shouldBe rightEdge
            route.target.x shouldBe rightEdge

            // Source ist oberhalb von Target (UPPER_ANCHOR < LOWER_ANCHOR).
            (route.source.y < route.target.y) shouldBe true

            // Beide Wegpunkte sitzen rechts neben der Box (x > rightEdge).
            route.waypoints shouldHaveSize 2
            route.waypoints.forEach { wp ->
                (wp.x > rightEdge) shouldBe true
            }
            // Wegpunkt-Y stimmen mit Source/Target-Y überein (orthogonale C-Form).
            route.waypoints[0].y shouldBe route.source.y
            route.waypoints[1].y shouldBe route.target.y
        }

        test("adjust ersetzt Self-Loop-Route durch C-Loop") {
            val selfAssoc =
                UmlAssociation(
                    id = "assoc1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Node"),
                            UmlAssociationEnd(typeId = "Node"),
                        ),
                )
            val originalRoute =
                EdgeRoute.OrthogonalRounded(
                    source = Point(300f, 60f),
                    target = Point(300f, 70f),
                    waypoints = listOf(Point(290f, 60f), Point(290f, 70f)),
                    cornerRadiusPx = 4f,
                )

            val adjusted =
                SelfLoopRouter.adjust(selfAssoc, originalRoute) { id ->
                    if (id == "Node") nodeLayout else null
                }

            adjusted.shouldBeInstanceOf<EdgeRoute.OrthogonalRounded>()
            // Die angepasste Route hat ihre Wegpunkte deutlich rechts neben der Box.
            (adjusted.source.x == 300f) shouldBe true
            adjusted.waypoints.forEach { wp ->
                (wp.x > 300f) shouldBe true
            }
        }

        test("adjust lässt Nicht-Self-Loop-Kanten unverändert durch") {
            val regularAssoc =
                UmlAssociation(
                    id = "assoc1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "NodeA"),
                            UmlAssociationEnd(typeId = "NodeB"),
                        ),
                )
            val originalRoute =
                EdgeRoute.Direct(source = Point(0f, 0f), target = Point(100f, 100f))

            val adjusted = SelfLoopRouter.adjust(regularAssoc, originalRoute) { null }

            adjusted shouldBe originalRoute
        }

        test("adjust lässt Self-Loop-Route unverändert, wenn Knoten nicht im Lookup ist") {
            val selfAssoc =
                UmlAssociation(
                    id = "assoc1",
                    ends =
                        listOf(
                            UmlAssociationEnd(typeId = "Missing"),
                            UmlAssociationEnd(typeId = "Missing"),
                        ),
                )
            val originalRoute =
                EdgeRoute.Direct(source = Point(0f, 0f), target = Point(100f, 100f))

            val adjusted = SelfLoopRouter.adjust(selfAssoc, originalRoute) { null }

            adjusted shouldBe originalRoute
        }
    })
