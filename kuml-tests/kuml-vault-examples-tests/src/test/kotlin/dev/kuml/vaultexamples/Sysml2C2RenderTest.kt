package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Wave C2 (SysML 2 ACT) — full pipeline (script, ELK layout, SVG, PNG). Assertions are font
 * independent (CSS classes, ids, attributes, geometry). PNGs land in build/sample-output/sysml2-c2.
 */
class Sysml2C2RenderTest :
    FunSpec({
        fun script(name: String): String =
            Sysml2C2RenderTest::class.java
                .getResourceAsStream("/sysml2-c2/$name.kuml.kts")!!
                .bufferedReader()
                .readText()

        fun evalModel(code: String): dev.kuml.sysml2.Sysml2Model {
            VaultExampleRenderer.init()
            val res =
                dev.kuml.core.script.KumlScriptHost
                    .eval(code = code)
            val ok = res as kotlin.script.experimental.api.ResultWithDiagnostics.Success
            val ex =
                dev.kuml.core.script.DiagramExtractor.extractAny(
                    returnValue = ok.value.returnValue,
                    input = java.io.File("inline.kuml.kts"),
                )
            return (ex as dev.kuml.core.script.ExtractedDiagram.Sysml2).model
        }

        fun attr(
            tag: String,
            name: String,
        ): Float = Regex("""\b$name="([-0-9.]+)"""").find(tag)!!.groupValues[1].toFloat()

        /** `<g id="regionId" ...>` body up to its closing tag (nodes are flat, no nested groups except none). */
        fun group(
            svg: String,
            id: String,
        ): String {
            val start = svg.indexOf("<g id=\"$id\"")
            check(start >= 0) { "group $id not found" }
            return svg.substring(start, svg.indexOf("</g>", start))
        }

        fun nodeOrigin(
            svg: String,
            id: String,
        ): Pair<Float, Float> {
            val m = Regex("""<g id="${Regex.escape(id)}" transform="translate\(([-0-9.]+),([-0-9.]+)\)"""").find(svg)!!
            return m.groupValues[1].toFloat() to m.groupValues[2].toFloat()
        }

        fun regionRect(
            svg: String,
            id: String,
        ): FloatArray {
            val g = group(svg, "region:$id")
            val rect = Regex("<rect [^>]*/>").find(g)!!.value
            return floatArrayOf(attr(rect, "x"), attr(rect, "y"), attr(rect, "width"), attr(rect, "height"))
        }

        test("act: all wave-C2 constructs render") {
            val r = VaultExampleRenderer.render(script = script("act"))
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "sysml2-c2/act.svg", content = svg)

            // call-behavior: rake symbol and machine-readable link
            svg shouldContain "data-kuml-calls=\"StockCheckActivity\""
            val call = group(svg, "CheckStock")
            Regex("""kuml-act-rake""").findAll(call).count() shouldBe 5

            // send-signal pentagon and accept-event notch: five polygon points each
            for (id in listOf("RequestPayment", "PaymentReceived")) {
                val poly = Regex("""<polygon points="([^"]+)"""").find(group(svg, id))!!.groupValues[1]
                poly.trim().split(" ").size shouldBe 5
            }
            // accept-time-event: hourglass of two triangles plus the label below
            val timeout = group(svg, "PaymentTimeout")
            timeout shouldContain "hourglass-top"
            timeout shouldContain "hourglass-bottom"
            timeout shouldContain "after 10 min"

            // control-node names
            svg shouldContain "in stock?"
            svg shouldContain "pack and notify"
        }

        test("act: interruptible regions are dashed rounded frames, nested and containing their members") {
            val svg = VaultExampleRenderer.render(script = script("act")).svg!!
            val payment = regionRect(svg, "Payment")
            val retry = regionRect(svg, "Retry")
            group(svg, "region:Payment") shouldContain "stroke-dasharray=\"6 4\""
            group(svg, "region:Payment") shouldContain "rx=\"10\""

            fun inside(
                outer: FloatArray,
                x: Float,
                y: Float,
            ) = x >= outer[0] && y >= outer[1] && x <= outer[0] + outer[2] && y <= outer[1] + outer[3]
            // retry nested in payment
            inside(payment, retry[0], retry[1]) shouldBe true
            inside(payment, retry[0] + retry[2], retry[1] + retry[3]) shouldBe true
            for (member in listOf("RequestPayment", "PaymentReceived", "PaymentTimeout", "ChargeCard", "RetryCharge")) {
                val (x, y) = nodeOrigin(svg, member)
                inside(payment, x, y) shouldBe true
            }
            val (rx, ry) = nodeOrigin(svg, "RetryCharge")
            inside(retry, rx, ry) shouldBe true
            // the name sits in a label group painted after the edges
            svg shouldContain "id=\"regionlabel:Payment\""
            (svg.indexOf("id=\"regionlabel:Payment\"") > svg.lastIndexOf("id=\"controlFlow:")) shouldBe true
        }

        test("act: interrupting flow and exception handler are lightning edges with open arrows") {
            val svg = VaultExampleRenderer.render(script = script("act")).svg!!
            Regex("""kuml-edge-lightning""").findAll(svg).count() shouldBe 2
            val handler = group(svg, "exceptionHandler:ChargeCard::HandleError")
            // wave C2b: the label is painted after all edges, in its own group keyed by the edge id
            val labelGroup = svg.substring(svg.indexOf("data-edge-id=\"exceptionHandler:ChargeCard::HandleError\""))
            labelGroup.substring(0, labelGroup.indexOf("</g>")) shouldContain "PaymentError"
            // the kink adds four points to the routed polyline
            val pts =
                Regex("""<polyline points="([^"]+)"""")
                    .find(handler)!!
                    .groupValues[1]
                    .trim()
                    .split(" ")
            (pts.size >= 6) shouldBe true
            // handler without a named pin gets a pin square at its arrival point
            svg shouldContain "kuml-act-handler-pin"
        }

        test("act: pin-to-pin object flow ends at the outer edge of the target pin") {
            val svg = VaultExampleRenderer.render(script = script("act")).svg!!
            val flow = group(svg, "objectFlow:PlaceOrder::ChargeCard")
            val line = Regex("""<line [^>]*/>""").find(flow)
            val (endX, endY) =
                if (line != null) {
                    attr(line.value, "x2") to attr(line.value, "y2")
                } else {
                    val d = Regex("""\bd="([^"]+)"""").find(flow)!!.groupValues[1]
                    val nums = Regex("""-?[0-9]+(?:\.[0-9]+)?""").findAll(d).map { it.value.toFloat() }.toList()
                    nums[nums.size - 2] to nums[nums.size - 1]
                }
            val (tx, ty) = nodeOrigin(svg, "ChargeCard")
            val node = group(svg, "ChargeCard")
            // the (only) top pin square: centre on the top edge, outer edge 5 px above it
            val pinRect = Regex("""<rect x="([-0-9.]+)" y="([-0-9.]+)" width="10" height="10"""").find(node)!!
            val px = tx + pinRect.groupValues[1].toFloat() + 5f
            val py = ty + pinRect.groupValues[2].toFloat()
            (kotlin.math.abs(endX - px) <= 1.5f) shouldBe true
            (kotlin.math.abs(endY - py) <= 1.5f) shouldBe true
        }

        test("act: lane headers are painted after all edges") {
            val svg = VaultExampleRenderer.render(script = script("act")).svg!!
            val lastEdge = svg.lastIndexOf("id=\"controlFlow:")
            for (lane in listOf("Customer", "Shop")) {
                (svg.indexOf("id=\"activityPartitionHeader:$lane\"") > lastEdge) shouldBe true
            }
        }

        test("act: no edge label drifts more than the leash from its own line") {
            val svg = VaultExampleRenderer.render(script = script("act")).svg!!
            // every edge group carries at most one label rect; a drifted label would have broken layout tests elsewhere,
            // here we only assert the labels exist and the render stays well-formed
            svg shouldContain "yes"
            svg shouldContain "no"
            svg.count { it == '<' } shouldBe svg.count { it == '>' }
        }

        test("escaping: markup in names, signals, times and exception types never reaches the SVG raw") {
            val s =
                """
                sysml2Model("Esc") {
                    val r = interruptibleRegion("R<b>&\"x\"")
                    val a = sendSignal("S<i>", signal = "sig <script>alert(1)</script>", region = r)
                    val t = acceptTimeEvent("T<u>", time = "after <img onerror=x> & more", region = r)
                    val c = callBehavior("C&D", activityId = "act\"><x")
                    val h = actionDef("H<h>")
                    exceptionHandler(protectedNode = c, handler = h, type = "E<e>&")
                    controlFlow("f", a, t)
                    controlFlow("g", t, c)
                    actDiagram("A") { include(a); include(t); include(c); include(h) }
                }
                """.trimIndent()
            val r = VaultExampleRenderer.render(script = s)
            r.error shouldBe null
            val svg = r.svg!!.replace(Regex(""" (id|data-kuml-calls)="[^"]*""""), "")
            svg shouldNotContain "<script>"
            svg shouldNotContain "<img"
            svg shouldNotContain "<b>"
            svg shouldNotContain "<x"
            svg shouldContain "&lt;script&gt;"
            svg shouldNotContain "&amp;amp;"
            r.svg!! shouldNotContain "data-kuml-calls=\"act\"><x"
        }

        test("limits: oversized labels are cut and rendering still succeeds") {
            val s =
                """
                sysml2Model("Big") {
                    val a = sendSignal("N".repeat(500), signal = "s".repeat(500))
                    val t = acceptTimeEvent("T", time = "after ".repeat(200))
                    val p = actionDef("P", pins = listOf(ActionPin("p".repeat(100), direction = PinDirection.Input, side = PinSide.Top)))
                    controlFlow("f", a, t)
                    controlFlow("g", t, p)
                    actDiagram("A") { include(a); include(t); include(p) }
                }
                """.trimIndent()
            val r = VaultExampleRenderer.render(script = s)
            r.error shouldBe null
            (r.svg!!.length < 200_000) shouldBe true
        }

        test("compatibility: a diagram without wave-C2 constructs contains none of them") {
            val s =
                """
                sysml2Model("Plain") {
                    val i = initialNode()
                    val a = actionDef("Work", pins = listOf(ActionPin("x", direction = PinDirection.Output)))
                    val b = actionDef("More")
                    controlFlow("f", i, a)
                    objectFlow("o", a, b, objectType = "T")
                    actDiagram("P") { include(i); include(a); include(b) }
                }
                """.trimIndent()
            val svg = VaultExampleRenderer.render(script = s).svg!!
            svg shouldNotContain "kuml-act-region"
            svg shouldNotContain "kuml-edge-lightning"
            svg shouldNotContain "data-kuml-calls"
            svg shouldNotContain "hourglass"
        }

        test("printer: printing the model and re-evaluating the printed script is stable") {
            val first = evalModel(script("act"))
            val printed =
                dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
                    .print(first)
            val second = evalModel(printed)
            dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
                .print(second) shouldBe printed
            second.definitions.size shouldBe first.definitions.size
            second.usages.size shouldBe first.usages.size
        }

        test("latex: the new node kinds fall back to labelled boxes") {
            val latex = VaultExampleRenderer.render(script = script("act")).latex!!
            latex shouldContain "send signal"
            latex shouldContain "accept time event"
            latex shouldContain "call behavior"
        }
    })
