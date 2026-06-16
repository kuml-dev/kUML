package dev.kuml.ai.tools.patch

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private fun addElementPatch(
    id: String,
    name: String = "TestClass",
) = ModelPatch.AddElement(
    patchId = ModelPatch.newId(),
    appliedAt = ModelPatch.nowIso(),
    diagramId = null,
    elementKind = "uml.class",
    elementId = id,
    name = name,
)

private fun removeElementPatch(id: String) =
    ModelPatch.RemoveElement(
        patchId = ModelPatch.newId(),
        appliedAt = ModelPatch.nowIso(),
        diagramId = null,
        elementId = id,
    )

private fun updateAttrPatch(
    ownerId: String,
    field: String,
    value: String,
) = ModelPatch.UpdateAttribute(
    patchId = ModelPatch.newId(),
    appliedAt = ModelPatch.nowIso(),
    diagramId = null,
    ownerId = ownerId,
    attributeId = ModelPatch.newId(),
    field = field,
    newValue = value,
)

private fun addRelPatch(
    relId: String,
    sourceId: String,
    targetId: String,
) = ModelPatch.AddRelationship(
    patchId = ModelPatch.newId(),
    appliedAt = ModelPatch.nowIso(),
    diagramId = null,
    relationshipKind = "uml.association",
    relationshipId = relId,
    sourceId = sourceId,
    targetId = targetId,
)

class PatchDiffTest :
    FunSpec({

        test("diff for AddElement reports one ElementChange with kind=added and null before") {
            runTest {
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx)
                val patch = addElementPatch("cls1")
                engine.buffer(patch)
                val diff = engine.diff(patch.patchId)

                diff.patchId shouldBe patch.patchId
                diff.elementChanges shouldHaveSize 1
                val change = diff.elementChanges.first()
                change.kind shouldBe "added"
                change.elementId shouldBe "cls1"
                change.before.shouldBeNull()
                change.after.shouldNotBeNull()
            }
        }

        test("diff for RemoveElement reports one ElementChange with kind=removed and null after") {
            runTest {
                val ctx =
                    AgentEditingContext(
                        AnyKumlModel.Uml(
                            name = "Test",
                            elements = listOf(UmlClass(id = "cls1", name = "Foo")),
                        ),
                    )
                val engine = PatchApplyEngine(context = ctx)
                val patch = removeElementPatch("cls1")
                engine.buffer(patch)
                val diff = engine.diff(patch.patchId)

                diff.elementChanges shouldHaveSize 1
                val change = diff.elementChanges.first()
                change.kind shouldBe "removed"
                change.after.shouldBeNull()
            }
        }

        test("diff for UpdateAttribute reports one ElementChange with kind=modified and both sides set") {
            runTest {
                val ctx =
                    AgentEditingContext(
                        AnyKumlModel.Uml(
                            name = "Test",
                            elements = listOf(UmlClass(id = "cls1", name = "Foo")),
                        ),
                    )
                val engine = PatchApplyEngine(context = ctx)
                val patch = updateAttrPatch("cls1", "guard", "x > 0")
                engine.buffer(patch)
                val diff = engine.diff(patch.patchId)

                diff.elementChanges shouldHaveSize 1
                val change = diff.elementChanges.first()
                change.kind shouldBe "modified"
                change.before.shouldNotBeNull()
                change.after.shouldNotBeNull()
            }
        }

        test("diff for AddRelationship reports one ElementChange for the relationship") {
            runTest {
                val ctx =
                    AgentEditingContext(
                        AnyKumlModel.Uml(
                            name = "Test",
                            elements =
                                listOf(
                                    UmlClass(id = "A", name = "A"),
                                    UmlClass(id = "B", name = "B"),
                                ),
                        ),
                    )
                val engine = PatchApplyEngine(context = ctx)
                val patch = addRelPatch("rel1", "A", "B")
                engine.buffer(patch)
                val diff = engine.diff(patch.patchId)

                diff.elementChanges shouldHaveSize 1
                diff.elementChanges.first().kind shouldBe "added"
                diff.elementChanges.first().elementId shouldBe "rel1"
            }
        }

        test("diff for unknown patchId returns empty changes") {
            runTest {
                val ctx = AgentEditingContext.emptyUml()
                val engine = PatchApplyEngine(context = ctx)
                val diff = engine.diff("nonexistent-id")
                diff.elementChanges.shouldHaveSize(0)
                diff.before.text shouldBe "(patch not found)"
            }
        }
    })
