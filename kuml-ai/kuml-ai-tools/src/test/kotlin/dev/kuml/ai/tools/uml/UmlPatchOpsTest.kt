package dev.kuml.ai.tools.uml

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class UmlPatchOpsTest :
    FunSpec({

        test("pure addClass on cloned UmlModel returns model with one more class") {
            val model = AnyKumlModel.emptyUml()
            val result = UmlPatchOps.addClass(model, "order_service", "OrderService", null, false)
            result.elements shouldHaveSize 1
            result.elements[0].name shouldBe "OrderService"
        }

        test("pure addAttribute updates the classifier in place") {
            val model = AnyKumlModel.emptyUml()
            val withClass = UmlPatchOps.addClass(model, "order", "Order", null, false)
            val withAttr =
                UmlPatchOps.addAttribute(
                    withClass,
                    "order",
                    "order_id",
                    "id",
                    "String",
                    dev.kuml.uml.Visibility.PRIVATE,
                    null,
                )
            withAttr.shouldNotBeNull()
            val cls = withAttr.elements[0] as UmlClass
            cls.attributes shouldHaveSize 1
            cls.attributes[0].name shouldBe "id"
        }

        test("pure remove cascades to dangling relationships") {
            val model = AnyKumlModel.emptyUml()
            val withClasses =
                UmlPatchOps.addClass(
                    UmlPatchOps.addClass(model, "cls_a", "A", null, false),
                    "cls_b",
                    "B",
                    null,
                    false,
                )
            val withAssoc =
                UmlPatchOps.addAssociation(
                    withClasses,
                    "assoc_ab",
                    "cls_a",
                    "cls_b",
                    null,
                    dev.kuml.uml.Multiplicity(),
                    dev.kuml.uml.Multiplicity(),
                )
            withAssoc.relationships shouldHaveSize 1
            // Remove class A — should cascade-remove the association
            val afterRemove = UmlPatchOps.removeElement(withAssoc, "cls_a")
            afterRemove.shouldNotBeNull()
            afterRemove.elements shouldHaveSize 1 // only B remains
            afterRemove.relationships shouldHaveSize 0 // association removed
        }

        test("pure rename leaves id unchanged") {
            val model = AnyKumlModel.emptyUml()
            val withClass = UmlPatchOps.addClass(model, "old_name", "OldName", null, false)
            val (renamed, oldName) = UmlPatchOps.renameElement(withClass, "old_name", "NewName")!!
            oldName shouldBe "OldName"
            renamed.elements[0].id shouldBe "old_name" // id unchanged
            renamed.elements[0].name shouldBe "NewName"
        }

        test("pure addAssociation injects multiplicity defaults") {
            val model = AnyKumlModel.emptyUml()
            val withClasses =
                UmlPatchOps.addClass(
                    UmlPatchOps.addClass(model, "src", "Source", null, false),
                    "tgt",
                    "Target",
                    null,
                    false,
                )
            val withAssoc =
                UmlPatchOps.addAssociation(
                    withClasses,
                    "a1",
                    "src",
                    "tgt",
                    null,
                    dev.kuml.uml.Multiplicity(),
                    dev.kuml.uml.Multiplicity(),
                )
            withAssoc.relationships shouldHaveSize 1
            val assoc = withAssoc.relationships[0] as dev.kuml.uml.UmlAssociation
            assoc.ends shouldHaveSize 2
        }
    })
