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
            val result =
                UmlPatchOps.addClass(
                    model = model,
                    id = "order_service",
                    name = "OrderService",
                    stereotype = null,
                    isAbstract = false,
                )
            result.elements shouldHaveSize 1
            result.elements[0].name shouldBe "OrderService"
        }

        test("pure addAttribute updates the classifier in place") {
            val model = AnyKumlModel.emptyUml()
            val withClass = UmlPatchOps.addClass(model = model, id = "order", name = "Order", stereotype = null, isAbstract = false)
            val withAttr =
                UmlPatchOps.addAttribute(
                    model = withClass,
                    classifierId = "order",
                    attrId = "order_id",
                    attrName = "id",
                    typeName = "String",
                    visibility = dev.kuml.uml.Visibility.PRIVATE,
                    defaultValue = null,
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
                    model = UmlPatchOps.addClass(model = model, id = "cls_a", name = "A", stereotype = null, isAbstract = false),
                    id = "cls_b",
                    name = "B",
                    stereotype = null,
                    isAbstract = false,
                )
            val withAssoc =
                UmlPatchOps.addAssociation(
                    model = withClasses,
                    assocId = "assoc_ab",
                    sourceId = "cls_a",
                    targetId = "cls_b",
                    name = null,
                    sourceMultiplicity = dev.kuml.uml.Multiplicity(),
                    targetMultiplicity = dev.kuml.uml.Multiplicity(),
                )
            withAssoc.relationships shouldHaveSize 1
            // Remove class A — should cascade-remove the association
            val afterRemove = UmlPatchOps.removeElement(model = withAssoc, elementId = "cls_a")
            afterRemove.shouldNotBeNull()
            afterRemove.elements shouldHaveSize 1 // only B remains
            afterRemove.relationships shouldHaveSize 0 // association removed
        }

        test("pure rename leaves id unchanged") {
            val model = AnyKumlModel.emptyUml()
            val withClass = UmlPatchOps.addClass(model = model, id = "old_name", name = "OldName", stereotype = null, isAbstract = false)
            val (renamed, oldName) = UmlPatchOps.renameElement(model = withClass, elementId = "old_name", newName = "NewName")!!
            oldName shouldBe "OldName"
            renamed.elements[0].id shouldBe "old_name" // id unchanged
            renamed.elements[0].name shouldBe "NewName"
        }

        test("pure addAssociation injects multiplicity defaults") {
            val model = AnyKumlModel.emptyUml()
            val withClasses =
                UmlPatchOps.addClass(
                    model = UmlPatchOps.addClass(model = model, id = "src", name = "Source", stereotype = null, isAbstract = false),
                    id = "tgt",
                    name = "Target",
                    stereotype = null,
                    isAbstract = false,
                )
            val withAssoc =
                UmlPatchOps.addAssociation(
                    model = withClasses,
                    assocId = "a1",
                    sourceId = "src",
                    targetId = "tgt",
                    name = null,
                    sourceMultiplicity = dev.kuml.uml.Multiplicity(),
                    targetMultiplicity = dev.kuml.uml.Multiplicity(),
                )
            withAssoc.relationships shouldHaveSize 1
            val assoc = withAssoc.relationships[0] as dev.kuml.uml.UmlAssociation
            assoc.ends shouldHaveSize 2
        }
    })
