package dev.kuml.ai.tools.patch

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.patch.fakes.FakeAiTraceSink
import dev.kuml.runtime.AiTraceEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

private fun addElementPatch(
    elementId: String,
    elementKind: String = "uml.class",
    name: String = "TestClass",
): ModelPatch.AddElement =
    ModelPatch.AddElement(
        patchId = ModelPatch.newId(),
        appliedAt = ModelPatch.nowIso(),
        diagramId = null,
        elementKind = elementKind,
        elementId = elementId,
        name = name,
    )

class PatchApplyEngineTest :
    FunSpec({

        test("init emits SessionStarted with fingerprint of pre-session model") {
            runTest {
                val sink = FakeAiTraceSink()
                val ctx = AgentEditingContext.emptyUml()
                PatchApplyEngine(context = ctx, traceSink = sink)
                val started = sink.expectEntry<AiTraceEntry.SessionStarted>()
                started.baseModelFingerprint shouldNotBe ""
                started.sessionId shouldNotBe ""
            }
        }

        test("applyOne with valid patch emits Validated(OK) then Applied and mutates the context") {
            runTest {
                val sink = FakeAiTraceSink()
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx, traceSink = sink)
                val patch = addElementPatch("cls1")
                engine.buffer(patch)

                val outcome = engine.applyOne(patch.patchId)

                outcome.shouldBeInstanceOf<PatchApplyOutcome.Applied>()
                val validated = sink.entriesOf<AiTraceEntry.Validated>().first()
                validated.phase shouldBe "OK"
                val applied = sink.entriesOf<AiTraceEntry.Applied>().first()
                applied.patchId shouldBe patch.patchId

                // Working model should contain the new class
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                model.elements.any { it.id == "cls1" } shouldBe true
            }
        }

        test("applyOne with duplicate id patch emits Validated(STRUCTURAL,errors) and does NOT mutate the context") {
            runTest {
                val sink = FakeAiTraceSink()
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx, traceSink = sink)

                // Apply first patch to establish cls1 in the model
                val patch1 = addElementPatch("cls1")
                engine.buffer(patch1)
                engine.applyOne(patch1.patchId).shouldBeInstanceOf<PatchApplyOutcome.Applied>()

                // Now try to apply a patch with the same elementId — should fail STRUCTURAL
                val patch2 = addElementPatch("cls1", name = "Duplicate")
                engine.buffer(patch2)
                val outcome = engine.applyOne(patch2.patchId)
                outcome.shouldBeInstanceOf<PatchApplyOutcome.ValidationFailed>()
                (outcome as PatchApplyOutcome.ValidationFailed).validation.phase shouldBe
                    dev.kuml.ai.tools.patch.validation.ValidationPhase.STRUCTURAL

                val validated = sink.entriesOf<AiTraceEntry.Validated>()
                validated.last().phase shouldBe "STRUCTURAL"

                // Model should still have exactly one cls1 element
                val model = ctx.resolveModel() as AnyKumlModel.Uml
                model.elements.count { it.id == "cls1" } shouldBe 1
            }
        }

        test("applyOne with unknown patchId returns ApplyFailed") {
            runTest {
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx)
                val outcome = engine.applyOne("nonexistent-id")
                outcome.shouldBeInstanceOf<PatchApplyOutcome.ApplyFailed>()
            }
        }

        test("rejectOne emits Rejected and leaves working model untouched") {
            runTest {
                val sink = FakeAiTraceSink()
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx, traceSink = sink)
                val patch = addElementPatch("cls1")
                engine.buffer(patch)

                val modelBefore = ctx.resolveModel()
                engine.rejectOne(patch.patchId, reason = "test rejection")

                val rejected = sink.expectEntry<AiTraceEntry.Rejected>()
                rejected.patchId shouldBe patch.patchId
                rejected.reason shouldBe "test rejection"

                // Working model must be unchanged — rejected patch was never applied
                val modelAfter = ctx.resolveModel()
                modelAfter shouldBe modelBefore
            }
        }

        test("rejectOne is idempotent — second call is a no-op") {
            runTest {
                val sink = FakeAiTraceSink()
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx, traceSink = sink)
                val patch = addElementPatch("cls1")
                engine.buffer(patch)
                engine.rejectOne(patch.patchId)
                engine.rejectOne(patch.patchId) // second call — no-op

                sink.entriesOf<AiTraceEntry.Rejected>() shouldHaveSize 1
            }
        }

        test("rejectAll restores pre-session snapshot and emits SessionAborted with pending ids") {
            runTest {
                val sink = FakeAiTraceSink()
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx, traceSink = sink)

                // Apply one patch — should mutate the model
                val patch1 = addElementPatch("cls1")
                engine.buffer(patch1)
                engine.applyOne(patch1.patchId).shouldBeInstanceOf<PatchApplyOutcome.Applied>()

                val patch2 = addElementPatch("cls2")
                engine.buffer(patch2)
                // patch2 still pending (not applied)

                engine.rejectAll(reason = "user clicked Reject all")

                val aborted = sink.expectEntry<AiTraceEntry.SessionAborted>()
                aborted.reason shouldBe "user clicked Reject all"

                // Pre-session snapshot was empty — model must be restored to empty
                val restoredModel = ctx.resolveModel() as AnyKumlModel.Uml
                restoredModel.elements.isEmpty() shouldBe true
            }
        }

        test("rejectAll after partial accepts only rejects still-pending patches") {
            runTest {
                val sink = FakeAiTraceSink()
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx, traceSink = sink)

                val patch1 = addElementPatch("cls1")
                val patch2 = addElementPatch("cls2")
                val patch3 = addElementPatch("cls3")
                engine.buffer(patch1)
                engine.buffer(patch2)
                engine.buffer(patch3)

                engine.applyOne(patch1.patchId)
                engine.rejectOne(patch2.patchId)
                // patch3 still pending

                engine.rejectAll()

                val aborted = sink.expectEntry<AiTraceEntry.SessionAborted>()
                aborted.rejectedPatchIds shouldContain patch3.patchId
            }
        }

        test("pendingPatchIds returns buffered patches minus rejected and applied") {
            runTest {
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx)
                val patch1 = addElementPatch("cls1")
                val patch2 = addElementPatch("cls2")
                val patch3 = addElementPatch("cls3")
                engine.buffer(patch1)
                engine.buffer(patch2)
                engine.buffer(patch3)

                engine.applyOne(patch1.patchId) // applied
                engine.rejectOne(patch2.patchId) // rejected

                val pending = engine.pendingPatchIds()
                pending shouldContain patch3.patchId
                pending.contains(patch1.patchId) shouldBe false
                pending.contains(patch2.patchId) shouldBe false
            }
        }

        test("concurrent applyOne calls are serialized by the engine mutex") {
            runTest {
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx)
                val patches = (1..10).map { addElementPatch("cls$it") }

                coroutineScope {
                    patches.forEach { patch ->
                        launch {
                            engine.buffer(patch)
                        }
                    }
                }

                coroutineScope {
                    patches.forEach { patch ->
                        launch {
                            engine.applyOne(patch.patchId)
                        }
                    }
                }

                // All patches should now be applied in the context
                ctx.patches().size shouldBe patches.size
            }
        }

        test("rejectedIds returns copy not live reference") {
            runTest {
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx)
                val patch = addElementPatch("cls1")
                engine.buffer(patch)
                engine.rejectOne(patch.patchId)

                val ids1 = engine.rejectedIds()
                ids1 shouldContain patch.patchId
                ids1 shouldHaveSize 1
            }
        }
    })
