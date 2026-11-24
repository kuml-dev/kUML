package dev.kuml.layout.bridge

import dev.kuml.layout.bridge.label.StmLabelWrap
import dev.kuml.sysml2.edge.StmEdgeAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL wave J: clearance invariants over the full bridge (no hard-coded layout result) for a reconstruction of the
 * "Doc 51" composite state machine, in both flow directions, and the wrap rule of its labels.
 */
class Doc51EdgeClearanceTest :
    FunSpec({
        timeout = 60_000L

        for (right in listOf(false, true)) {
            val flow = if (right) "right" else "down"

            test("flow $flow: no two edges run closer than 16 px side by side") {
                val (g, r) = Doc51Fixture.layout(right = right)
                detectViolations(result = r, graph = g).parallel shouldBe emptyList()
            }

            test("flow $flow: no edge comes closer than 12 px to a foreign node, the frame or the header band") {
                val (g, r) = Doc51Fixture.layout(right = right)
                detectViolations(result = r, graph = g).edgeNode shouldBe emptyList()
            }

            test("flow $flow: a clean layout does not trigger the second run") {
                val (g, r) = Doc51Fixture.layout(right = right)
                needsRelayout(result = r, graph = g) shouldBe false
            }
        }

        test("every wrapped line but the last of its part has at least three words (unless three words exceed 220 px)") {
            val m = Doc51Fixture.model()
            val limit = StmLabelWrap.maxChars(StmLabelWrap.DEFAULT_MAX_WIDTH_PX)
            for (usage in m.usages) {
                val parts = StmEdgeAdapter.transitionLabelParts(usage as dev.kuml.sysml2.TransitionUsage)
                val lines = StmLabelWrap.wrap(parts = parts)
                for ((i, line) in lines.withIndex()) {
                    val next = lines.getOrNull(i + 1) ?: continue
                    // Each part starts on its own line, so the last line of a part may be short; a line inside a part may not.
                    if (next.parts.first().kind != line.parts.last().kind) continue
                    val words = line.text.split(' ').filter { it.isNotEmpty() && !(it.endsWith(":") && it.length <= 4) }
                    (words.size >= 3 || line.text.length > limit) shouldBe true
                }
            }
        }
    })
