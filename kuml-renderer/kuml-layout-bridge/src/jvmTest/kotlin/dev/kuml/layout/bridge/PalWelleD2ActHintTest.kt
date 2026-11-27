package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.Sysml2LayoutFlow
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** PAL Welle D2 (10c): `wrapColumns` on an activity diagram with swimlanes is reported once, never silently dropped. */
class PalWelleD2ActHintTest :
    FunSpec({
        fun w012Messages(model: Sysml2Model): List<String> {
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val raw =
                ElkLayoutEngine().layout(
                    graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act),
                    hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = act),
                )
            val norm = Sysml2LayoutBridge.normalizeActLayout(model = model, diagram = act, layoutResult = raw)
            return norm.warnings.filter { it.code == LayoutCodes.ACTIVITY_HINT_UNSUPPORTED }.map { it.message }
        }

        fun modelWith(configure: dev.kuml.sysml2.dsl.Sysml2LayoutBuilder.() -> Unit): Sysml2Model =
            sysml2Model(name = "W012") {
                val lane = activityPartition(name = "Lane")
                val a = actionDef(name = "A", partition = lane)
                val b = actionDef(name = "B", partition = lane)
                controlFlow(name = "f", source = a, target = b)
                actDiagram(name = "D") {
                    include(a)
                    include(b)
                    layout(configure)
                }
            }

        fun w012Count(
            model: Sysml2Model,
            withHint: Boolean,
        ): Int {
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val raw =
                ElkLayoutEngine().layout(
                    graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act),
                    hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = act),
                )
            val norm = Sysml2LayoutBridge.normalizeActLayout(model = model, diagram = act, layoutResult = raw)
            check(withHint == (act.layout?.wrapColumns != null))
            return norm.warnings.count { it.code == LayoutCodes.ACTIVITY_HINT_UNSUPPORTED }
        }

        fun model(withHint: Boolean): Sysml2Model =
            sysml2Model(name = "W012") {
                val lane = activityPartition(name = "Lane")
                val a = actionDef(name = "A", partition = lane)
                val b = actionDef(name = "B", partition = lane)
                controlFlow(name = "f", source = a, target = b)
                actDiagram(name = "D") {
                    include(a)
                    include(b)
                    if (withHint) layout { wrapColumns = 2 }
                }
            }

        test("W012 is reported exactly once for wrapColumns on a swimlane activity diagram") {
            w012Count(model(withHint = true), withHint = true) shouldBe 1
        }

        test("no W012 without the hint") {
            w012Count(model(withHint = false), withHint = false) shouldBe 0
        }

        test("PAL Welle X3: maxWidth and aspectRatio are reported once each, in a fixed order") {
            val messages =
                w012Messages(
                    modelWith {
                        maxWidth = 900
                        aspectRatio = 1.5
                    },
                )
            messages.size shouldBe 2
            messages[0].contains("'maxWidth'") shouldBe true
            messages[1].contains("'aspectRatio'") shouldBe true
        }

        test("PAL Welle X3: all three ignored options give three findings, one per option") {
            w012Messages(
                modelWith {
                    wrapColumns = 2
                    maxWidth = 900
                    aspectRatio = 1.5
                },
            ).size shouldBe 3
        }

        test("PAL Welle X3: honoured options give no W012") {
            w012Messages(modelWith { direction = Sysml2LayoutFlow.Down }).size shouldBe 0
            w012Messages(modelWith { labelMaxWidth = 200 }).size shouldBe 0
            w012Messages(modelWith { }).size shouldBe 0
        }
    })
