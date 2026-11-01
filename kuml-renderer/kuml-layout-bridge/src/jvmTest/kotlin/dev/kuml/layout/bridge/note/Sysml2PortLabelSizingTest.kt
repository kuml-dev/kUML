package dev.kuml.layout.bridge.note

import dev.kuml.layout.bridge.Sysml2LayoutBridge
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** PAL wave 2C-b2: estimates of wrapped port labels grow with their lines (basis of the port pitch). */
class Sysml2PortLabelSizingTest :
    FunSpec(body = {
        test("a wrapped port label is as high as all its lines and as wide as its widest line") {
            val one = Sysml2LayoutBridge.estimateLabelSize(text = "abcdef")
            val two = Sysml2LayoutBridge.estimateLabelSize(text = "abcdef\nab")
            two.width shouldBe one.width
            two.height shouldBe one.height * 2f
        }

        test("ibdPortLabelText wraps instead of cutting") {
            val text =
                Sysml2LayoutBridge.ibdPortLabelText(
                    name = "supplyVoltageMeasurement",
                    typeName = "HighVoltagePowerPortType",
                    conjugated = true,
                )
            text.replace("\n", "").replace(" ", "") shouldBe "supplyVoltageMeasurement:~HighVoltagePowerPortType"
            (text.lines().size <= 2) shouldBe true
        }
    })
