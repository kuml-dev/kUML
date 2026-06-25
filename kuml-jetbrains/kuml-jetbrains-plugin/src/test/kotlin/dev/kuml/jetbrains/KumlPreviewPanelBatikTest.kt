package dev.kuml.jetbrains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * V2.0.30 — Unit tests for the Batik JSVGCanvas-based KumlPreviewPanel.
 *
 * All tests are standalone (no IntelliJ runtime required). Batik's
 * SAXSVGDocumentFactory is exercised directly via [KumlPreviewPanel.parseSvg].
 *
 * Eight tests total:
 *  1.  parseSvg: valid minimal SVG string → non-null SVGDocument
 *  2.  parseSvg: malformed XML → returns null, no exception thrown
 *  3.  Panel initial status is STATUS_RENDERING
 *  4.  JSVGCanvas can be accessed without throwing
 *  5.  Toolbar has exactly 6 buttons (Fit Window, Fit Width, Fit Height, 100%, Zoom In, Zoom Out)
 *  6.  dispose() is idempotent — calling twice does not throw
 *  7.  scheduleUpdate() on a disposed panel does not throw
 *  8.  STATUS_RENDERING, STATUS_READY, STATUS_NO_DIAGRAM are distinct non-null strings
 */
class KumlPreviewPanelBatikTest :
    FunSpec({

        // ── 1. parseSvg: valid SVG → non-null document ────────────────────────

        test("parseSvg: valid minimal SVG string returns non-null SVGDocument") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                val svgString =
                    """
                    <svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
                       <rect x="10" y="10" width="80" height="80" fill="blue"/>
                    </svg>
                    """.trimIndent()
                val doc = panel.parseSvg(svgString)
                doc shouldNotBe null
            } finally {
                panel.dispose()
            }
        }

        // ── 2. parseSvg: malformed XML → null, no exception ───────────────────

        test("parseSvg: malformed XML returns null without throwing") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                val badSvg = "<not-valid-xml-at-all <<<>>>"
                val doc = panel.parseSvg(badSvg)
                doc shouldBe null
            } finally {
                panel.dispose()
            }
        }

        // ── 3. Panel initial status is STATUS_RENDERING ───────────────────────

        test("Panel: initial currentStatus is STATUS_RENDERING") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                panel.currentStatus shouldBe KumlPreviewPanel.STATUS_RENDERING
            } finally {
                panel.dispose()
            }
        }

        // ── 4. JSVGCanvas accessible without throwing ─────────────────────────

        test("Panel: svgCanvas lazy field can be accessed without throwing") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                // Accessing the lazy property must not throw (even in headless mode)
                val canvas = panel.svgCanvas
                canvas shouldNotBe null
            } finally {
                panel.dispose()
            }
        }

        // ── 5. Toolbar has exactly 6 buttons ─────────────────────────────────

        test("Panel: toolbar contains exactly 6 JButton components") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            try {
                var buttonCount = 0
                for (i in 0 until panel.toolbar.componentCount) {
                    if (panel.toolbar.getComponent(i) is javax.swing.JButton) {
                        buttonCount++
                    }
                }
                buttonCount shouldBe 6
            } finally {
                panel.dispose()
            }
        }

        // ── 6. dispose() is idempotent ────────────────────────────────────────

        test("Panel: dispose() can be called twice without throwing") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            panel.dispose()
            // Second call must not throw
            panel.dispose()
        }

        // ── 7. scheduleUpdate on disposed panel does not throw ────────────────

        test("Panel: scheduleUpdate() on a disposed panel does not throw") {
            val panel = KumlPreviewPanel(debounceMs = 50L)
            panel.dispose()
            // Must silently return — not throw IllegalStateException
            panel.scheduleUpdate("classDiagram {}", "test.kuml.kts")
        }

        // ── 8. Status constants are distinct non-null strings ─────────────────

        test("STATUS_RENDERING, STATUS_READY, STATUS_NO_DIAGRAM are distinct non-null strings") {
            KumlPreviewPanel.STATUS_RENDERING shouldNotBe null
            KumlPreviewPanel.STATUS_READY shouldNotBe null
            KumlPreviewPanel.STATUS_NO_DIAGRAM shouldNotBe null

            val statuses =
                setOf(
                    KumlPreviewPanel.STATUS_RENDERING,
                    KumlPreviewPanel.STATUS_READY,
                    KumlPreviewPanel.STATUS_NO_DIAGRAM,
                )
            statuses.size shouldBe 3
        }
    })
