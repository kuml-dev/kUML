package dev.kuml.desktop.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun nodeText(n: PathNode): String =
    when (n) {
        is PathNode.MoveTo -> "M${n.x},${n.y}"
        is PathNode.LineTo -> "L${n.x},${n.y}"
        is PathNode.CurveTo -> "C${n.x1},${n.y1},${n.x2},${n.y2},${n.x3},${n.y3}"
        is PathNode.ArcTo ->
            "A${n.horizontalEllipseRadius},${n.verticalEllipseRadius},${n.isMoreThanHalf},${n.isPositiveArc},${n.arcStartX},${n.arcStartY}"
        PathNode.Close -> "Z"
        else -> n.toString()
    }

/** Compact textual form of all paths of [icon] (name, stroke width, fill/stroke flags, nodes). */
fun pathFingerprint(icon: ImageVector): String {
    val out = mutableListOf<String>()

    fun walk(node: VectorNode) {
        when (node) {
            is VectorGroup -> node.forEach { walk(it) }
            is VectorPath -> {
                val head = "${node.name} w=${node.strokeLineWidth} fill=${node.fill != null} stroke=${node.stroke != null}: "
                out += head + node.pathData.joinToString(" ") { nodeText(it) }
            }
        }
    }
    walk(icon.root)
    return out.joinToString("\n")
}

class KumlIconsTest :
    FunSpec({

        val menuIcons: List<Pair<String, ImageVector>> =
            listOf(
                "DocumentNew" to KumlIcons.DocumentNew,
                "FolderOpen" to KumlIcons.FolderOpen,
                "WorkspaceNew" to KumlIcons.WorkspaceNew,
                "Workspace" to KumlIcons.Workspace,
                "Save" to KumlIcons.Save,
                "SaveAs" to KumlIcons.SaveAs,
                "Quit" to KumlIcons.Quit,
                "Undo" to KumlIcons.Undo,
                "Redo" to KumlIcons.Redo,
                "Search" to KumlIcons.Search,
                "Help" to KumlIcons.Help,
                "Info" to KumlIcons.Info,
                "Settings" to KumlIcons.Settings,
                "Ai" to KumlIcons.Ai,
                "ChatNew" to KumlIcons.ChatNew,
                "SimStart" to KumlIcons.SimStart,
                "SimStop" to KumlIcons.SimStop,
                "Server" to KumlIcons.Server,
                "Plugins" to KumlIcons.Plugins,
            )

        fun paths(icon: ImageVector): List<VectorPath> {
            val out = mutableListOf<VectorPath>()

            fun walk(node: VectorNode) {
                when (node) {
                    is VectorGroup -> node.forEach { walk(it) }
                    is VectorPath -> out += node
                }
            }
            walk(icon.root)
            return out
        }

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
                "Close" to KumlIcons.Close,
                // Dialog buttons (b2a)
                "Check" to KumlIcons.Check,
                "Reject" to KumlIcons.Reject,
                "Edit" to KumlIcons.Edit,
                "Delete" to KumlIcons.Delete,
                "Send" to KumlIcons.Send,
                "Add" to KumlIcons.Add,
                "Warning" to KumlIcons.Warning,
                "Copy" to KumlIcons.Copy,
                "Refresh" to KumlIcons.Refresh,
                "Document" to KumlIcons.Document,
                "PageLandscape" to KumlIcons.PageLandscape,
            ) + menuIcons

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

        test("the 19 menu icons have unique names") {
            menuIcons.size shouldBe 19
            menuIcons.map { it.second.name }.toSet().size shouldBe 19
        }

        test("menu icons are stroke-only at width 2, except the filled dots of Info and Server") {
            val dotIcons = setOf("Info", "Server")
            menuIcons.forEach { (name, icon) ->
                withClue(name) {
                    paths(icon).forEach { path ->
                        if (path.stroke != null) {
                            path.strokeLineWidth shouldBe 2f
                        } else {
                            withClue("filled path ${path.name}") { (name in dotIcons) shouldBe true }
                        }
                    }
                }
            }
        }

        test("menu icons keep every drawn coordinate inside 0..24") {
            menuIcons.forEach { (name, icon) ->
                withClue(name) {
                    paths(icon).flatMap { it.pathData }.forEach { n ->
                        val values: List<Float> =
                            when (n) {
                                is PathNode.MoveTo -> listOf(n.x, n.y)
                                is PathNode.LineTo -> listOf(n.x, n.y)
                                is PathNode.CurveTo -> listOf(n.x1, n.y1, n.x2, n.y2, n.x3, n.y3)
                                is PathNode.ArcTo -> listOf(n.arcStartX, n.arcStartY)
                                else -> emptyList()
                            }
                        values.all { it in 0f..24f } shouldBe true
                    }
                }
            }
        }

        test("SaveSvg and SavePng paths are unchanged by the badge refactoring") {
            val base =
                "w=2.0 fill=false stroke=true: M4.0,15.0 L4.0,19.0 L20.0,19.0 L20.0,15.0 M8.0,3.0 L8.0,13.0 M4.0,9.0 L8.0,13.0 L12.0,9.0"
            pathFingerprint(KumlIcons.SaveSvg) shouldBe
                listOf(
                    "KumlSaveSvg.base $base",
                    "KumlSaveSvg.curve w=2.0 fill=false stroke=true: M16.0,8.0 C17.0,3.0,21.0,8.0,22.0,3.0",
                    "KumlSaveSvg.anchors w=0.0 fill=true stroke=false: " +
                        "M15.0,7.0 L17.0,7.0 L17.0,9.0 L15.0,9.0 Z M21.0,2.0 L23.0,2.0 L23.0,4.0 L21.0,4.0 Z",
                ).joinToString("\n")
            pathFingerprint(KumlIcons.SavePng) shouldBe
                listOf(
                    "KumlSavePng.base $base",
                    "KumlSavePng.frame w=1.5 fill=false stroke=true: M15.75,2.75 L21.25,2.75 L21.25,8.25 L15.75,8.25 Z",
                    "KumlSavePng.cells w=0.0 fill=true stroke=false: " +
                        "M16.5,3.5 L19.0,3.5 L19.0,6.0 L16.5,6.0 Z M19.0,6.0 L21.0,6.0 L21.0,8.0 L19.0,8.0 Z",
                ).joinToString("\n")
        }

        test("Save is the plain tray: the SaveSvg base without any badge path") {
            paths(KumlIcons.Save).size shouldBe 1
        }
    })
