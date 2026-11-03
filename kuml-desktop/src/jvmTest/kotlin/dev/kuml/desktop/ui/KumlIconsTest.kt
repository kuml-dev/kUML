package dev.kuml.desktop.ui

import androidx.compose.ui.graphics.vector.ImageVector
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KumlIconsTest :
    FunSpec({

        val allIcons: List<Pair<String, ImageVector>> =
            listOf(
                "ZoomIn" to KumlIcons.ZoomIn,
                "ZoomOut" to KumlIcons.ZoomOut,
                "FitToWindow" to KumlIcons.FitToWindow,
                "ViewSource" to KumlIcons.ViewSource,
                "ViewSplit" to KumlIcons.ViewSplit,
                "ViewDiagram" to KumlIcons.ViewDiagram,
                // V3.x — Live-Simulation (SimulationBar)
                "SimStep" to KumlIcons.SimStep,
                "SimAutoAdvance" to KumlIcons.SimAutoAdvance,
                "SimPause" to KumlIcons.SimPause,
                "SimReset" to KumlIcons.SimReset,
                "SimInspector" to KumlIcons.SimInspector,
                "EditGuard" to KumlIcons.EditGuard,
                "RevertGuard" to KumlIcons.RevertGuard,
                // Export aus der Vorschau
                "SaveSvg" to KumlIcons.SaveSvg,
                "SavePng" to KumlIcons.SavePng,
                "PdfDocument" to KumlIcons.PdfDocument,
            )

        test("all icons use a 24x24 viewport") {
            allIcons.forEach { (name, icon) ->
                withClue(name) {
                    icon.viewportWidth shouldBe 24f
                    icon.viewportHeight shouldBe 24f
                }
            }
        }

        test("all icons contain at least one drawn path (not an empty vector)") {
            allIcons.forEach { (name, icon) ->
                withClue(name) {
                    (icon.root.size > 0) shouldBe true
                }
            }
        }

        test("export icons stay inside the 24x24 viewport and are distinct") {
            fun coords(icon: ImageVector): List<Float> {
                val out = mutableListOf<Float>()

                fun walk(node: androidx.compose.ui.graphics.vector.VectorNode) {
                    when (node) {
                        is androidx.compose.ui.graphics.vector.VectorGroup -> node.forEach { walk(it) }
                        is androidx.compose.ui.graphics.vector.VectorPath ->
                            node.pathData.forEach { n ->
                                when (n) {
                                    is androidx.compose.ui.graphics.vector.PathNode.MoveTo -> out += listOf(n.x, n.y)
                                    is androidx.compose.ui.graphics.vector.PathNode.LineTo -> out += listOf(n.x, n.y)
                                    is androidx.compose.ui.graphics.vector.PathNode.CurveTo ->
                                        out += listOf(n.x1, n.y1, n.x2, n.y2, n.x3, n.y3)
                                    else -> Unit
                                }
                            }
                    }
                }
                walk(icon.root)
                return out
            }
            listOf(KumlIcons.SaveSvg, KumlIcons.SavePng, KumlIcons.PdfDocument).forEach { icon ->
                withClue(icon.name) {
                    val c = coords(icon)
                    (c.isNotEmpty()) shouldBe true
                    c.all { it in 0f..24f } shouldBe true
                }
            }
            KumlIcons.SaveSvg shouldNotBe KumlIcons.SavePng
            KumlIcons.PdfDocument shouldNotBe KumlIcons.SaveSvg
        }

        test("ZoomIn and ZoomOut are distinct vectors") {
            KumlIcons.ZoomIn shouldNotBe KumlIcons.ZoomOut
        }

        test("ViewSource and ViewDiagram are distinct vectors (mirrored, not identical)") {
            KumlIcons.ViewSource shouldNotBe KumlIcons.ViewDiagram
        }

        test("SimAutoAdvance and SimPause are distinct vectors") {
            KumlIcons.SimAutoAdvance shouldNotBe KumlIcons.SimPause
        }

        test("repeated access returns the same cached instance (lazy val, not rebuilt per call)") {
            (KumlIcons.ZoomIn === KumlIcons.ZoomIn) shouldBe true
        }
    })
