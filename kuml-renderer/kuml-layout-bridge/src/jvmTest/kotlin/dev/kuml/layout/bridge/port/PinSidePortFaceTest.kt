package dev.kuml.layout.bridge.port

import dev.kuml.sysml2.PinSide
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PinSidePortFaceTest :
    FunSpec({
        test("explicit sides map to the face of the same name, independent of the pin direction") {
            for (isOutput in listOf(true, false)) {
                PinSide.Left.toPortFace(isOutput = isOutput) shouldBe PortFace.Left
                PinSide.Right.toPortFace(isOutput = isOutput) shouldBe PortFace.Right
                PinSide.Top.toPortFace(isOutput = isOutput) shouldBe PortFace.Top
                PinSide.Bottom.toPortFace(isOutput = isOutput) shouldBe PortFace.Bottom
            }
        }

        test("Auto keeps the historic placement: inputs left, outputs right") {
            PinSide.Auto.toPortFace(isOutput = false) shouldBe PortFace.Left
            PinSide.Auto.toPortFace(isOutput = true) shouldBe PortFace.Right
        }

        test("the outward normal of every mapped face points away from the box side") {
            val n = { side: PinSide -> PortEdgeClamp.normal(side.toPortFace(isOutput = true)) }
            n(PinSide.Left).x shouldBe -1f
            n(PinSide.Right).x shouldBe 1f
            n(PinSide.Top).y shouldBe -1f
            n(PinSide.Bottom).y shouldBe 1f
        }
    })
