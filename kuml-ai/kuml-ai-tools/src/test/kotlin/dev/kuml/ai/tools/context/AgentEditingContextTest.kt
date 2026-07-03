package dev.kuml.ai.tools.context

import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class AgentEditingContextTest :
    FunSpec({

        test("empty UML context starts with no patches and null currentDiagramId") {
            val ctx = AgentEditingContext.emptyUml()
            runTest {
                ctx.patches() shouldHaveSize 0
                ctx.currentDiagramId.shouldBeNull()
            }
        }

        test("applyPatch records exactly one ModelPatch entry") {
            val ctx = AgentEditingContext.emptyUml()
            runTest {
                val patch = makePatch("elem1")
                ctx.applyPatch(patch) { model ->
                    (model as AnyKumlModel.Uml).copy(
                        elements = model.elements + UmlClass(id = "elem1", name = "Foo"),
                    )
                }
                ctx.patches() shouldHaveSize 1
            }
        }

        test("applyPatch surfaces the same patchId in the result envelope") {
            val ctx = AgentEditingContext.emptyUml()
            runTest {
                val patch = makePatch("elem1")
                val result =
                    ctx.applyPatch(patch) { model ->
                        (model as AnyKumlModel.Uml).copy(
                            elements = model.elements + UmlClass(id = "elem1", name = "Foo"),
                        )
                    }
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                result.patchId shouldBe patch.patchId
            }
        }

        test("parallel applyPatch calls do not interleave") {
            val ctx = AgentEditingContext.emptyUml()
            runTest {
                coroutineScope {
                    repeat(50) { n ->
                        launch {
                            val id = "class_$n"
                            val patch = makePatch(id)
                            ctx.applyPatch(patch) { model ->
                                (model as AnyKumlModel.Uml).copy(
                                    elements = model.elements + UmlClass(id = id, name = "Class$n"),
                                )
                            }
                        }
                    }
                }
                // All 50 patches should be recorded exactly once
                ctx.patches() shouldHaveSize 50
                // Model should have 50 elements
                val uml = ctx.resolveModel() as AnyKumlModel.Uml
                uml.elements shouldHaveSize 50
                // All ids should be unique
                uml.elements
                    .map { it.id }
                    .toSet()
                    .size shouldBe 50
            }
        }

        test("setCurrentDiagramId returns the previous id") {
            val ctx = AgentEditingContext.emptyUml()
            runTest {
                ctx.setCurrentDiagramId("diag-1")
                val prev = ctx.setCurrentDiagramId("diag-2")
                prev shouldBe "diag-1"
                ctx.currentDiagramId shouldBe "diag-2"
            }
        }

        test("snapshot captures model patches and currentDiagramId") {
            val ctx = AgentEditingContext.emptyUml()
            runTest {
                ctx.setCurrentDiagramId("diag-A")
                val patch = makePatch("e1")
                ctx.applyPatch(patch) { m ->
                    (m as AnyKumlModel.Uml).copy(elements = m.elements + UmlClass(id = "e1", name = "Svc"))
                }
                val snap = ctx.snapshot()
                snap.currentDiagramId shouldBe "diag-A"
                snap.patches shouldHaveSize 1
            }
        }

        test("resetTo restores the captured working state exactly") {
            val ctx = AgentEditingContext.emptyUml()
            runTest {
                // Capture empty snapshot
                val snap = ctx.snapshot()
                // Add something
                val patch = makePatch("e1")
                ctx.applyPatch(patch) { m ->
                    (m as AnyKumlModel.Uml).copy(elements = m.elements + UmlClass(id = "e1", name = "Svc"))
                }
                (ctx.resolveModel() as AnyKumlModel.Uml).elements shouldHaveSize 1
                // Roll back
                ctx.resetTo(snap)
                (ctx.resolveModel() as AnyKumlModel.Uml).elements shouldHaveSize 0
                ctx.patches() shouldHaveSize 0
            }
        }

        test("applyPatch with failing mutate function does NOT append a patch") {
            val ctx = AgentEditingContext.emptyUml()
            runTest {
                val patch = makePatch("boom")
                ctx.applyPatch(patch) { _ -> throw RuntimeException("Simulated failure") }
                ctx.patches() shouldHaveSize 0
            }
        }

        test("emptyC4 factory yields a C4Model variant") {
            val ctx = AgentEditingContext.emptyC4()
            runTest {
                ctx.resolveModel().shouldBeInstanceOf<AnyKumlModel.C4>()
            }
        }

        test("emptySysml2 factory yields a Sysml2Model variant") {
            val ctx = AgentEditingContext.emptySysml2()
            runTest {
                ctx.resolveModel().shouldBeInstanceOf<AnyKumlModel.Sysml2>()
            }
        }
    }) {
    companion object {
        private fun makePatch(elementId: String): ModelPatch.AddElement =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = "test-diagram",
                elementKind = "uml.class",
                elementId = elementId,
                name = elementId,
            )
    }
}
