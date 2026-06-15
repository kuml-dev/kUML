package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * V2.x — Bbox-Overlap-Clustering von [Sysml2EdgeRenderer.computeLabelStackIndices].
 *
 * Das vorige V11.x-Clustering nutzte rein Euklidische Midpoint-Distanz mit
 * 40 px Radius. Damit blieb der PAR-Newton-Fall ungelöst: drei parallele
 * Bindings mit Midpoint-Abstand 70–80 px (über dem Radius), aber Label-
 * Background-Rechtecken bis 110 px Breite, die visuell überlappten. Die
 * neue Bbox-Logik vergleicht echte Rechteck-Überschneidung statt
 * Punkt-Distanz und gibt den richtigen Stack-Index-Versatz zurück.
 */
class Sysml2EdgeRendererStackIndexTest :
    StringSpec({

        // Hilfs-Konstruktor für eine vertikale Direct-Edge — Label-Anker
        // sitzt am Midpoint dieser Linie.
        fun verticalEdge(
            id: String,
            x: Float,
            y1: Float = 100f,
            y2: Float = 200f,
        ): Triple<EdgeId, EdgeRoute, String?> =
            Triple(
                EdgeId(id),
                EdgeRoute.Direct(source = Point(x, y1), target = Point(x, y2)),
                null,
            )

        fun verticalEdge(
            id: String,
            x: Float,
            label: String,
            y1: Float = 100f,
            y2: Float = 200f,
        ): Triple<EdgeId, EdgeRoute, String?> =
            Triple(
                EdgeId(id),
                EdgeRoute.Direct(source = Point(x, y1), target = Point(x, y2)),
                label,
            )

        "PAR-Newton — drei parallele Bindings mit langen Labels stapeln vertikal" {
            // Pin-Abstand 80 px (über altem 40-px-Euklid-Radius), aber
            // `a_to_acceleration` ist ~110 px breit → kollidiert mit dem
            // `m_to_mass`-Background-Rect. Erwartung: F=0, m=0, a=1 — oder
            // mindestens: a sitzt in einem anderen Band als das räumlich
            // überlappende m-Label.
            val edges =
                listOf(
                    verticalEdge("b1", x = 140f, label = "F_to_force"),
                    verticalEdge("b2", x = 220f, label = "m_to_mass"),
                    verticalEdge("b3", x = 300f, label = "a_to_acceleration"),
                )
            val stack = Sysml2EdgeRenderer.computeLabelStackIndices(edges)
            // Die kritische Aussage: nicht alle drei sitzen im selben Band.
            // Mindestens ein Sibling muss versetzt sein, sonst hätte sich
            // gegenüber V11.x nichts geändert und das Vault-Beispiel würde
            // weiter kollidieren.
            stack.values.toSet().size shouldBe 2
        }

        "alleinstehende kurze Labels bleiben in Band 0" {
            // Drei klar getrennte Edges (X-Abstand 300 px, Labels je ~30 px
            // halfWidth) — kein Overlap, kein Stack.
            val edges =
                listOf(
                    verticalEdge("e1", x = 50f, label = "a"),
                    verticalEdge("e2", x = 400f, label = "b"),
                    verticalEdge("e3", x = 750f, label = "c"),
                )
            val stack = Sysml2EdgeRenderer.computeLabelStackIndices(edges)
            stack[EdgeId("e1")] shouldBe 0
            stack[EdgeId("e2")] shouldBe 0
            stack[EdgeId("e3")] shouldBe 0
        }

        "REQ-traceability — close-midpoint siblings (`«derive»` + `«containment»`) bleiben geclustert" {
            // Reproduziert den V11.x-Ausgangsfall: zwei parallele Edges
            // 30 px auseinander, Stereotyp-Labels ~30 px halfWidth. Bbox
            // überschneidet sich → b1 bleibt Band 0, b2 wandert in Band 1.
            val edges =
                listOf(
                    verticalEdge("derive", x = 100f, label = "«derive»"),
                    verticalEdge("contain", x = 130f, label = "«containment»"),
                )
            val stack = Sysml2EdgeRenderer.computeLabelStackIndices(edges)
            stack[EdgeId("derive")] shouldBe 0
            stack[EdgeId("contain")] shouldBe 1
        }

        "label-lose Edges weit auseinander bleiben getrennt (kein Bbox-Overlap)" {
            // halfWidth = 0 → reine Anker-Position zählt. 30 px Abstand
            // > 6 px Padding-Slack → kein Overlap → beide in Band 0. Das
            // ist gegenüber der V11.x-Euklid-Logik (40 px Radius) eine
            // bewusste Aufweichung: label-lose Edges rendern ohnehin kein
            // Label (siehe early-return in Sysml2EdgeRenderer.render), der
            // Stack-Index ist für sie ohne sichtbare Wirkung.
            val edges =
                listOf(
                    verticalEdge("e1", x = 100f),
                    verticalEdge("e2", x = 130f),
                )
            val stack = Sysml2EdgeRenderer.computeLabelStackIndices(edges)
            stack[EdgeId("e1")] shouldBe 0
            stack[EdgeId("e2")] shouldBe 0
        }
    })
