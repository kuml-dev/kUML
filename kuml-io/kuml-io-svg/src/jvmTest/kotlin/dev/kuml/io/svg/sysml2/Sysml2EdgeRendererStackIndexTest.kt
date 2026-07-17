package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.sysml2.edge.Sysml2EdgeRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
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

        "computeLabelStackAssignments — Sibling mit abweichendem natürlichen Anker rendert auf der Cluster-Baseline" {
            // Reproduziert den Bug hinter zwei überlappenden UML-STM-Transition-
            // Labels, die auf denselben Endzustand zulaufen (Vault-Beispiel
            // „Mitgliedschafts-Lebenszyklus" — austritt()/abmeldung() +
            // ausschluss()): beide Edges überlappen im Label-Bbox-Test und
            // clustern korrekt, aber ihre natürlichen Anker-Y-Werte (aus
            // unterschiedlich geformten Routen) liegen bereits 15 px
            // auseinander. Würde [Sysml2EdgeRenderer.render] den Stack-
            // Offset einfach auf den jeweils EIGENEN Anker addieren (die alte
            // computeLabelStackIndices-only-Logik), ergäbe das nur einen
            // Bruchteil des beabsichtigten Abstands statt eines vollen
            // Bandes. Der Fix: Index-0 behält seinen eigenen natürlichen
            // Anker (anchorOverride == null — Bit-für-Bit unverändert), aber
            // jeder spätere Sibling bekommt (eigenes mx, Cluster-Baseline-my)
            // als expliziten Override zurück, sodass der Stack-Offset in
            // [Sysml2EdgeRenderer.render] von derselben Basis aus misst.
            val first = verticalEdge("austritt", x = 160f, label = "austritt() / abmeldung()", y1 = 60f, y2 = 160f) // natural my = 110
            val second = verticalEdge("ausschluss", x = 153f, label = "ausschluss()", y1 = 75f, y2 = 115f) // natural my = 95
            val assignments = Sysml2EdgeRenderer.computeLabelStackAssignments(listOf(first, second))

            val firstAssignment = assignments.getValue(EdgeId("austritt"))
            firstAssignment.index shouldBe 0
            firstAssignment.anchorOverride shouldBe null

            val secondAssignment = assignments.getValue(EdgeId("ausschluss"))
            secondAssignment.index shouldBe 1
            // y must be the FIRST member's natural anchor (110), not the
            // second edge's own natural anchor (95) — that is the whole fix.
            secondAssignment.anchorOverride?.second shouldBe 110f
            secondAssignment.anchorOverride?.first shouldBe 153f
        }

        "estimateLabelHalfWidth — leeres/blankes Label hat Halbbreite 0" {
            Sysml2EdgeRenderer.estimateLabelHalfWidth(null) shouldBe 0f
            Sysml2EdgeRenderer.estimateLabelHalfWidth("") shouldBe 0f
        }

        "estimateLabelHalfWidth — spiegelt die approxW-Formel aus emitText" {
            // (12 * 6.2 + 6) / 2 = 40.2 — dieselbe Formel, die
            // dev.kuml.io.svg.KumlSvgRenderer.umlStmWidenForLabelOverhang beim
            // Bemessen der nötigen Rahmen-Verbreiterung verwendet (Fix für das
            // Vault-Beispiel „streichung()", das früher über den linken
            // State-Machine-Rahmenrand hinausragte statt dass der Rahmen dafür
            // breiter gemacht wurde).
            Sysml2EdgeRenderer.estimateLabelHalfWidth("streichung()") shouldBe (40.2f plusOrMinus 0.01f)
        }
    })
