package dev.kuml.layout.bridge

import dev.kuml.sysml2.projection.IbdAttributeCompartment
import dev.kuml.sysml2.projection.IbdAttributeLine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class Sysml2IbdAttributeHeightTest :
    FunSpec({
        fun lines(n: Int) = (1..n).map { IbdAttributeLine(name = "a$it", typeName = "T", value = null) }

        test("empty compartment adds no height") {
            Sysml2LayoutBridge.ibdAttributeCompartmentHeight(IbdAttributeCompartment.EMPTY) shouldBe 0f
        }

        test("without overflow: padding plus one line height per attribute") {
            val h = Sysml2LayoutBridge.ibdAttributeCompartmentHeight(IbdAttributeCompartment(lines = lines(2), hiddenCount = 0))
            h shouldBe Sysml2LayoutBridge.IBD_ATTR_PAD + 2 * Sysml2LayoutBridge.IBD_ATTR_LINE_H
        }

        test("with overflow: one extra line for the '... n more' text") {
            val h = Sysml2LayoutBridge.ibdAttributeCompartmentHeight(IbdAttributeCompartment(lines = lines(8), hiddenCount = 3))
            h shouldBe Sysml2LayoutBridge.IBD_ATTR_PAD + 9 * Sysml2LayoutBridge.IBD_ATTR_LINE_H
        }
    })
