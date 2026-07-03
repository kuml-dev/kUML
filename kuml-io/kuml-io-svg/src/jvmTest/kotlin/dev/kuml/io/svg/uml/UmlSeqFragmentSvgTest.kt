package dev.kuml.io.svg.uml

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.InteractionOperator
import dev.kuml.uml.MessageSort
import dev.kuml.uml.UmlCombinedFragment
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlInteractionOperand
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage
import io.kotest.core.spec.style.FunSpec

/**
 * SVG-Rendering-Tests für UML-Sequenzdiagramm Combined Fragments.
 *
 * Fokus: `break_` nested inside `loop` — Bug-Regression aus V3.x.y.
 *
 * Vorher waren zwei Bugs:
 *  1. Falsche Render-Reihenfolge: BREAK wurde vor LOOP gerendert (flache Liste),
 *     das äußere LOOP-Frame übermalt visuell das innere BREAK-Frame.
 *  2. Identischer Stil: BREAK und LOOP teilten `stroke-dasharray="6 4"` und
 *     dieselbe Breite → BREAK war optisch nicht von LOOP unterscheidbar.
 *
 * Nach dem Fix:
 *  - Äußere Frames (LOOP) werden vor verschachtelten (BREAK) gerendert.
 *  - BREAK nutzt eine solide Umrandung (kein stroke-dasharray) und einen
 *    leichten Hintergrundfüller (#eef6ff).
 */
class UmlSeqFragmentSvgTest :
    FunSpec({

        // ── Modell ────────────────────────────────────────────────────────────
        //  Engineer --NL task--> LLM         (seq 1, top-level)
        //  [loop k ≤ 3]
        //    LLM --kUML code--> kotlinc      (seq 2)
        //    [break [success]]
        //      kotlinc --render--> Renderer  (seq 3)
        //      Renderer --SVG--> Engineer    (seq 4, reply)
        //    [/break]
        //    kotlinc --repair prompt--> LLM  (seq 5, reply)
        //  [/loop]

        val engineer = UmlLifeline(id = "GCR::ll::Engineer", name = "Engineer", isActor = true)
        val llm = UmlLifeline(id = "GCR::ll::LLM", name = "LLM")
        val kotlinc = UmlLifeline(id = "GCR::ll::kotlinc", name = "kotlinc")
        val renderer = UmlLifeline(id = "GCR::ll::Renderer", name = "SVG Renderer")

        val msgNLTask =
            UmlMessage(
                id = "GCR::msg::1",
                label = "NL task",
                fromLifelineId = engineer.id,
                toLifelineId = llm.id,
                sort = MessageSort.SYNC_CALL,
                sequence = 1,
            )
        val msgKumlCode =
            UmlMessage(
                id = "GCR::msg::2",
                label = "kUML code",
                fromLifelineId = llm.id,
                toLifelineId = kotlinc.id,
                sort = MessageSort.SYNC_CALL,
                sequence = 2,
            )
        val msgRender =
            UmlMessage(
                id = "GCR::msg::3",
                label = "render",
                fromLifelineId = kotlinc.id,
                toLifelineId = renderer.id,
                sort = MessageSort.SYNC_CALL,
                sequence = 3,
            )
        val msgSvg =
            UmlMessage(
                id = "GCR::msg::4",
                label = "SVG",
                fromLifelineId = renderer.id,
                toLifelineId = engineer.id,
                sort = MessageSort.REPLY,
                sequence = 4,
            )
        val msgRepair =
            UmlMessage(
                id = "GCR::msg::5",
                label = "repair prompt",
                fromLifelineId = kotlinc.id,
                toLifelineId = llm.id,
                sort = MessageSort.REPLY,
                sequence = 5,
            )

        // BREAK wraps messages 3+4 (render + SVG)
        val breakFrag =
            UmlCombinedFragment(
                id = "GCR::frag::1",
                operator = InteractionOperator.BREAK,
                operands =
                    listOf(
                        UmlInteractionOperand(
                            guard = "[success]",
                            messageIds = listOf(msgRender.id, msgSvg.id),
                        ),
                    ),
            )

        // LOOP wraps messages 2+5, contains BREAK as nested fragment
        val loopFrag =
            UmlCombinedFragment(
                id = "GCR::frag::2",
                operator = InteractionOperator.LOOP,
                operands =
                    listOf(
                        UmlInteractionOperand(
                            guard = "k ≤ 3",
                            messageIds = listOf(msgKumlCode.id, msgRepair.id),
                            fragmentIds = listOf(breakFrag.id),
                        ),
                    ),
            )

        val interaction =
            UmlInteraction(
                id = "GCR",
                name = "GCR — Generate-Compile-Repair",
                lifelines = listOf(engineer, llm, kotlinc, renderer),
                messages = listOf(msgNLTask, msgKumlCode, msgRender, msgSvg, msgRepair),
                // Mimics DSL output: BREAK added before LOOP (built during LOOP operand construction)
                fragments = listOf(breakFrag, loopFrag),
            )

        val diagram =
            KumlDiagram(
                name = "GCR — Generate-Compile-Repair",
                type = DiagramType.SEQUENCE,
                elements = listOf(interaction),
            )

        // Lifeline height: HEAD(40) + (5+1)*ROW(32) + 2-operands*BAND(24) + TAIL(40) = 320
        fun fakeLayout() =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = 1L,
                canvas = Size(750f, 420f),
                nodes =
                    mapOf(
                        NodeId(engineer.id) to
                            NodeLayout(bounds = Rect(Point(20f, 20f), Size(140f, 320f))),
                        NodeId(llm.id) to
                            NodeLayout(bounds = Rect(Point(200f, 20f), Size(140f, 320f))),
                        NodeId(kotlinc.id) to
                            NodeLayout(bounds = Rect(Point(380f, 20f), Size(140f, 320f))),
                        NodeId(renderer.id) to
                            NodeLayout(bounds = Rect(Point(560f, 20f), Size(140f, 320f))),
                    ),
                edges = emptyMap(),
                groups = emptyMap(),
            )

        // ── Tests ─────────────────────────────────────────────────────────────

        test("break_ inside loop renders BREAK keyword and LOOP keyword") {
            val svg = KumlSvgRenderer.toSvg(diagram, fakeLayout(), PlainTheme())
            assert(svg.contains("BREAK")) { "Expected 'BREAK' keyword in pentagon — not found in SVG" }
            assert(svg.contains("LOOP")) { "Expected 'LOOP' keyword in pentagon — not found in SVG" }
            SampleOutput.write("uml-seq/loop-with-break.svg", svg)
        }

        test("break_ frame uses solid border — no stroke-dasharray on BREAK group") {
            val svg = KumlSvgRenderer.toSvg(diagram, fakeLayout(), PlainTheme())
            val breakGroupStart = svg.indexOf("id=\"${breakFrag.id}\"")
            assert(breakGroupStart >= 0) { "BREAK fragment <g> not found in SVG" }
            // The fragment <g> contains only flat child elements (<rect>, <polygon>, <text>)
            // — no nested <g> — so the first </g> is the closing tag of the fragment group.
            val breakGroupEnd = svg.indexOf("</g>", breakGroupStart) + 4
            val breakGroupSvg = svg.substring(breakGroupStart, breakGroupEnd)
            assert(!breakGroupSvg.contains("stroke-dasharray")) {
                "BREAK frame should use solid border (no stroke-dasharray). " +
                    "Fragment SVG:\n$breakGroupSvg"
            }
        }

        test("LOOP frame retains dashed border") {
            val svg = KumlSvgRenderer.toSvg(diagram, fakeLayout(), PlainTheme())
            val loopGroupStart = svg.indexOf("id=\"${loopFrag.id}\"")
            assert(loopGroupStart >= 0) { "LOOP fragment <g> not found in SVG" }
            val loopGroupEnd = svg.indexOf("</g>", loopGroupStart) + 4
            val loopGroupSvg = svg.substring(loopGroupStart, loopGroupEnd)
            assert(loopGroupSvg.contains("stroke-dasharray")) {
                "LOOP frame should retain dashed border (stroke-dasharray). " +
                    "Fragment SVG:\n$loopGroupSvg"
            }
        }

        test("LOOP is rendered before BREAK in SVG — BREAK appears on top") {
            val svg = KumlSvgRenderer.toSvg(diagram, fakeLayout(), PlainTheme())
            val loopPos = svg.indexOf("id=\"${loopFrag.id}\"")
            val breakPos = svg.indexOf("id=\"${breakFrag.id}\"")
            assert(loopPos >= 0) { "LOOP fragment not found in SVG" }
            assert(breakPos >= 0) { "BREAK fragment not found in SVG" }
            assert(loopPos < breakPos) {
                "LOOP must be rendered (appear) before BREAK in SVG so BREAK paints on top. " +
                    "loopPos=$loopPos, breakPos=$breakPos"
            }
        }

        test("BREAK frame uses light fill to distinguish from enclosing LOOP frame") {
            val svg = KumlSvgRenderer.toSvg(diagram, fakeLayout(), PlainTheme())
            val breakGroupStart = svg.indexOf("id=\"${breakFrag.id}\"")
            assert(breakGroupStart >= 0) { "BREAK fragment <g> not found in SVG" }
            val breakGroupEnd = svg.indexOf("</g>", breakGroupStart) + 4
            val breakGroupSvg = svg.substring(breakGroupStart, breakGroupEnd)
            // BREAK frame rect must use a light background (#eef6ff), not fill="none"
            assert(breakGroupSvg.contains("#eef6ff")) {
                "BREAK frame rect should use light fill (#eef6ff), not fill='none'. " +
                    "Fragment SVG:\n$breakGroupSvg"
            }
        }

        test("nested BREAK frame is narrower than enclosing LOOP frame") {
            val svg = KumlSvgRenderer.toSvg(diagram, fakeLayout(), PlainTheme())

            // Extract width from rect attribute: width="NNN"
            fun extractRectWidth(fragId: String): Float {
                val groupStart = svg.indexOf("id=\"$fragId\"")
                assert(groupStart >= 0) { "Fragment <g id=\"$fragId\"> not found in SVG" }
                val groupEnd = svg.indexOf("</g>", groupStart) + 4
                val groupSvg = svg.substring(groupStart, groupEnd)
                val rectStart = groupSvg.indexOf("<rect")
                assert(rectStart >= 0) { "No <rect> found in fragment group for $fragId" }
                val rectEnd = groupSvg.indexOf(">", rectStart)
                val rectTag = groupSvg.substring(rectStart, rectEnd)
                val widthMatch = Regex("""width="([0-9.]+)"""").find(rectTag)
                assert(widthMatch != null) { "No width attribute found in <rect> for $fragId: $rectTag" }
                return widthMatch!!.groupValues[1].toFloat()
            }

            val loopWidth = extractRectWidth(loopFrag.id)
            val breakWidth = extractRectWidth(breakFrag.id)

            assert(breakWidth < loopWidth) {
                "Nested BREAK frame must be narrower than enclosing LOOP frame. " +
                    "loopWidth=$loopWidth, breakWidth=$breakWidth"
            }
        }
    })
