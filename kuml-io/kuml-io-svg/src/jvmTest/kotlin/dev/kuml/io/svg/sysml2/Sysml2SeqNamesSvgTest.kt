package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Nacharbeit 63 — names of execution specifications, combined fragments and interaction uses.
 * Font independent: only presence, order, coordinates and text content are asserted, never byte-exact output.
 */
class Sysml2SeqNamesSvgTest :
    StringSpec({
        fun render(
            model: Sysml2Model,
            name: String,
        ): String {
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val layout = ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq))
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = seq, layoutResult = layout, theme = PlainTheme())
            SampleOutput.write(filename = "sysml2-seq-names/$name.svg", content = svg)
            return svg
        }

        fun block(
            svg: String,
            id: String,
        ): String {
            val start = svg.indexOf("<g id=\"$id\"")
            check(start >= 0) { "no group $id" }
            return svg
                .substring(start, svg.indexOf("</g>", start))
                .replace(Regex(">\\s+"), ">")
                .replace(Regex("\\s+<"), "<")
        }

        // The group id itself is escaped by the builder; look it up by its stable prefix, not by the escaped text.
        fun blockWithPrefix(
            svg: String,
            prefix: String,
        ): String {
            val start = svg.indexOf("<g id=\"$prefix")
            check(start >= 0) { "no group starting with $prefix" }
            return svg
                .substring(start, svg.indexOf("</g>", start))
                .replace(Regex(">\\s+"), ">")
                .replace(Regex("\\s+<"), "<")
        }

        fun attr(
            tag: String,
            name: String,
        ): Float =
            Regex("\\b$name=\"(-?[0-9.]+)\"")
                .find(tag)
                ?.groupValues
                ?.get(1)
                ?.toFloat() ?: error("no $name in $tag")

        fun texts(group: String): List<String> = Regex("<text[^>]*>([^<]*)</text>").findAll(group).map { it.groupValues[1] }.toList()

        fun execModel(
            execName: String,
            third: Boolean = true,
        ): Sysml2Model =
            sysml2Model(name = "Exec") {
                val client = lifelineDef(name = "Client")
                val server = lifelineDef(name = "Server")
                val store = lifelineDef(name = "Store")
                message(label = "request", source = client, target = server, seqNo = 0)
                message(label = "load", source = server, target = store, seqNo = 1)
                message(label = "reply", source = server, target = client, seqNo = 2)
                executionSpec(name = execName, lifeline = server, startSeqNo = 0, endSeqNo = 2)
                seqDiagram(name = "S") {
                    include(client)
                    include(server)
                    if (third) include(store)
                }
            }

        "execution spec name is drawn right of the bar and above its top edge" {
            val svg = render(execModel(execName = "handleRequest"), "exec")
            val g = block(svg, "executionSpec:Server-0-2")
            g shouldContain "<title>handleRequest</title>"
            val rect = Regex("<rect[^>]*fill=\"white\"[^>]*/>").findAll(g).map { it.value }.first { it.contains("kuml-class") }
            val barRight = attr(rect, "x") + attr(rect, "width")
            val barTop = attr(rect, "y")
            val text = Regex("<text[^>]*>handleRequest</text>").find(g) ?: error("no name text")
            attr(text.value, "x") shouldBeGreaterThan barRight
            (attr(text.value, "y") <= barTop) shouldBe true
        }

        "execution spec name longer than 16 characters is clipped with an ellipsis, title keeps the full name" {
            val long = "handleVeryLongRequestName"
            val svg = render(execModel(execName = long), "exec-long")
            val g = block(svg, "executionSpec:Server-0-2")
            g shouldContain "<title>$long</title>"
            g shouldContain "…"
            g shouldNotContain ">$long</text>"
        }

        "blank execution spec name draws neither text nor title" {
            val svg = render(execModel(execName = "  "), "exec-blank")
            val g = block(svg, "executionSpec:Server-0-2")
            g shouldNotContain "<text"
            g shouldNotContain "<title"
        }

        "execution spec name is escaped in text and title" {
            val svg = render(execModel(execName = "<script>&"), "exec-escape")
            val g = block(svg, "executionSpec:Server-0-2")
            g shouldNotContain "<script>"
            g shouldContain "&lt;script&gt;&amp;"
        }

        "execution spec keeps only the title when the next lifeline is too close" {
            val spec =
                dev.kuml.sysml2.ExecutionSpecificationUsage(
                    id = "executionSpec:x",
                    name = "handleRequest",
                    qualifiedName = "handleRequest",
                    lifelineId = "x",
                    startSeqNo = 0,
                    endSeqNo = 1,
                )
            val layout =
                dev.kuml.layout.NodeLayout(
                    bounds =
                        dev.kuml.layout.Rect(
                            origin = dev.kuml.layout.Point(x = 0f, y = 0f),
                            size = dev.kuml.layout.Size(width = 140f, height = 300f),
                        ),
                )
            val builder =
                dev.kuml.io.svg
                    .SvgBuilder(pretty = false)
            renderExecutionSpec(execSpec = spec, lifelineLayout = layout, builder = builder, nextLifelineCx = 70f + 5f + 20f)
            val out = builder.toString()
            out shouldContain "<title>handleRequest</title>"
            out shouldNotContain "<text"
        }

        fun fragModel(
            fragName: String,
            guard: String?,
        ): Sysml2Model =
            sysml2Model(name = "Frag") {
                val a = lifelineDef(name = "Client")
                val b = lifelineDef(name = "Server")
                message(label = "m0", source = a, target = b, seqNo = 0)
                message(label = "m1", source = b, target = a, seqNo = 1)
                combinedFragment(name = fragName, operator = CombinedFragmentOperator.Opt, startSeqNo = 0, endSeqNo = 1, guard = guard)
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                }
            }

        "combined fragment name is right-aligned inside the frame on the tag row" {
            val svg = render(fragModel(fragName = "checkout", guard = "ok"), "frag")
            val g = block(svg, "combinedFragment:checkout")
            g shouldContain "<title>checkout</title>"
            val text = Regex("<text[^>]*>checkout</text>").find(g) ?: error("no name text")
            text.value shouldContain "text-anchor=\"end\""
            val rect = Regex("<rect[^>]*/>").find(g)!!.value
            (attr(text.value, "x") <= attr(rect, "x") + attr(rect, "width")) shouldBe true
        }

        "combined fragment name is clipped so it never reaches the first guard" {
            val long = "n".repeat(120)
            val svg = render(fragModel(fragName = long, guard = "x".repeat(112)), "frag-yield")
            val g = block(svg, "combinedFragment:checkout".replace("checkout", long))
            g shouldContain "<title>$long</title>"
            g shouldNotContain ">$long</text>"
            val guardBg = Regex("<rect[^>]*fill=\"white\"[^>]*stroke=\"none\"[^>]*/>").find(g)!!.value
            val guardRight = attr(guardBg, "x") + attr(guardBg, "width")
            val nameText = Regex("<text[^>]*text-anchor=\"end\"[^>]*>([^<]*)</text>").find(g)
            if (nameText != null) {
                val len = nameText.groupValues[1].length
                (attr(nameText.value, "x") - len * 6.5f) shouldBeGreaterThan guardRight
            }
        }

        "frame and tag geometry is identical with and without a fragment name" {
            val named = block(render(fragModel(fragName = "checkout", guard = "ok"), "frag-n"), "combinedFragment:checkout")
            val blank = block(render(fragModel(fragName = " ", guard = "ok"), "frag-b"), "combinedFragment: ")
            Regex("<rect[^>]*/>").find(named)!!.value shouldBe Regex("<rect[^>]*/>").find(blank)!!.value
            Regex("<polygon[^>]*/>").find(named)!!.value shouldBe Regex("<polygon[^>]*/>").find(blank)!!.value
        }

        "blank fragment name draws no title and an explicit name is escaped" {
            val blank = block(render(fragModel(fragName = " ", guard = "ok"), "frag-blank"), "combinedFragment: ")
            blank shouldNotContain "<title"
            val esc = blockWithPrefix(render(fragModel(fragName = "<script>&", guard = "ok"), "frag-escape"), "combinedFragment:")
            esc shouldNotContain "<script>"
            esc shouldContain "&lt;script&gt;&amp;"
        }

        "a blank guard draws no empty brackets" {
            for (guard in listOf("", "   ")) {
                val svg = render(fragModel(fragName = "checkout", guard = guard), "frag-blank-guard")
                svg shouldNotContain "[]"
            }
        }

        fun refModel(
            useName: String,
            refersTo: String,
        ): Sysml2Model =
            sysml2Model(name = "Ref") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "b")
                message(label = "m0", source = a, target = b, seqNo = 0)
                interactionUse(name = useName, refersTo = refersTo, lifelines = listOf(a, b), seqNo = 1)
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                }
            }

        "interaction use shows 'name : refersTo' and keeps the 28 px frame" {
            val svg = render(refModel(useName = "payFlow", refersTo = "Payment"), "ref")
            val g = block(svg, "interactionUse:payFlow")
            texts(g) shouldContain "payFlow : Payment"
            g shouldContain "<title>payFlow : Payment</title>"
            attr(Regex("<rect[^>]*/>").find(g)!!.value, "height") shouldBe 28f
        }

        "interaction use with name equal to refersTo, or blank name, shows refersTo only" {
            val same = block(render(refModel(useName = "Payment", refersTo = "Payment"), "ref-same"), "interactionUse:Payment")
            texts(same) shouldContain "Payment"
            same shouldNotContain " : "
            val blank = block(render(refModel(useName = " ", refersTo = "Payment"), "ref-blank"), "interactionUse: ")
            texts(blank) shouldContain "Payment"
            blank shouldNotContain " : "
        }

        "interaction use name is escaped" {
            val g = blockWithPrefix(render(refModel(useName = "<script>&", refersTo = "P"), "ref-escape"), "interactionUse:")
            g shouldNotContain "<script>"
            g shouldContain "&lt;script&gt;&amp;"
        }

        "frame width does not depend on the name length" {
            val a =
                Regex(
                    "<rect[^>]*/>",
                ).find(block(render(refModel(useName = "x", refersTo = "Payment"), "ref-w1"), "interactionUse:x"))!!.value
            val b =
                Regex(
                    "<rect[^>]*/>",
                ).find(
                    block(render(refModel(useName = "y".repeat(30), refersTo = "Payment"), "ref-w2"), "interactionUse:${"y".repeat(30)}"),
                )!!
                    .value
            attr(a, "width") shouldBe attr(b, "width")
            attr(a, "width") shouldBeLessThanOrEqual attr(b, "width")
        }
    })
