package dev.kuml.layout

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Stellt die Default-Werte von [LayoutHints] fest. Dient als Regressions-Guard:
 * jeder Default-Drift wird hier sichtbar, und Aufrufer können sich auf das
 * dokumentierte Default-Verhalten verlassen.
 */
class LayoutHintsDefaultsTest :
    FunSpec({

        test("LayoutHints.DEFAULT.mergeEdges ist false (opt-in für Fan-In-Konsolidierung)") {
            // Default-off, weil Trunk-Routing andere Diagramme weniger lesbar
            // machen kann; explizit zu setzen ist eine bewusste Entscheidung.
            LayoutHints.DEFAULT.mergeEdges shouldBe false
        }

        test("LayoutHints() ohne Argumente hat mergeEdges = false") {
            LayoutHints().mergeEdges shouldBe false
        }

        test("mergeEdges = true ist über Copy aktivierbar (opt-in)") {
            val hints = LayoutHints.DEFAULT.copy(mergeEdges = true)
            hints.mergeEdges shouldBe true
            // Andere Defaults unverändert.
            hints.direction shouldBe LayoutDirection.TopToBottom
            hints.defaultEdgeStyle shouldBe EdgeRouteStyle.OrthogonalRounded
        }
    })
