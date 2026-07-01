package dev.kuml.io.svg

import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * V3.0.11 — regression guard for the UML-Activity-Diagramm CSS gap.
 *
 * `UmlV11Svg.renderUmlActivityNode` emits SVG elements with the classes
 * `kuml-action`, `kuml-decision`, `kuml-fork-bar`, `kuml-pseudostate` and
 * `kuml-final-outer`. Before V3.0.11 the `SvgDocument.buildDefs` CSS block
 * forgot to define any of them — the result was solid-black-filled shapes
 * (SVG default for unstyled `<rect>` / `<polygon>`) that swallowed both the
 * action text and the arrow tips of incoming edges.
 *
 * This test rebuilds a minimal activity diagram with one node of each kind
 * and asserts that **every CSS class referenced by the activity renderer is
 * actually defined in the `<style>` block of the produced SVG**. If a new
 * activity-node kind ever appears, add it here so the CSS gap cannot
 * silently regress.
 */
class UmlActivityNodeSvgTest :
    FunSpec({

        test("activity-diagram CSS classes are all defined in <style>") {
            // One node of each kind — ensures the renderer emits every CSS
            // class we expect to find styled.
            val kinds = UmlActivityNodeKind.values()
            val nodes =
                kinds.mapIndexed { i, kind ->
                    UmlActivityNode(id = "n$i", name = "node-$i", kind = kind)
                }
            val diagram = KumlDiagram(name = "ActivityCssSmokeTest", elements = nodes)
            val layoutResult =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(1000f, 200f),
                    nodes =
                        nodes
                            .mapIndexed { i, n ->
                                NodeId(n.id) to NodeLayout(bounds = Rect(Point(20f + i * 130f, 40f), Size(120f, 80f)))
                            }.toMap(),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )

            val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, PlainTheme())

            // Every class the renderer references must have a CSS rule in
            // the <style> block, otherwise the shape falls back to black-
            // filled and the diagram is unreadable.
            svg shouldContain ".kuml-action {"
            svg shouldContain ".kuml-decision {"
            svg shouldContain ".kuml-fork-bar {"
            svg shouldContain ".kuml-pseudostate {"
            svg shouldContain ".kuml-final-outer {"

            // Sanity: the action box must use the background colour as fill —
            // not the SVG default (black). PlainTheme background is #FFFFFF.
            svg shouldContain ".kuml-action { fill: #FFFFFF;"
            svg shouldContain ".kuml-decision { fill: #FFFFFF;"
            svg shouldContain ".kuml-final-outer { fill: #FFFFFF;"
            // Initial-Marker / Fork-Join-Bar are filled with the foreground
            // colour (canonical UML notation: a solid dot / solid bar).
            svg shouldContain ".kuml-pseudostate { fill: #000000;"
            svg shouldContain ".kuml-fork-bar { fill: #000000;"
        }
    })
