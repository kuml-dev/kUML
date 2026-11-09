package dev.kuml.sysml2

import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Nacharbeit 48 (N-IBD-F e/f): the direction a port is drawn with. */
class EffectivePortDirectionTest :
    FunSpec(body = {
        val model =
            sysml2Model(name = "Pd") {
                val item = attributeDef(name = "Water")
                portDef(name = "FIn") { flowProperty(name = "w", typeId = item.id, direction = PortDirection.In) }
                portDef(name = "FMixed") {
                    flowProperty(name = "a", typeId = item.id, direction = PortDirection.In)
                    flowProperty(name = "b", typeId = item.id, direction = PortDirection.Out)
                }
                portDef(name = "Plain")
                partDef(name = "Host") {
                    port(name = "derived", typeId = "FIn")
                    port(name = "conj", typeId = "FIn", conjugated = true)
                    port(name = "mixed", typeId = "FMixed")
                    port(name = "plain", typeId = "Plain")
                    port(name = "declared", typeId = "FIn", direction = PortDirection.Out)
                }
            }

        fun direction(name: String): PortDirection? =
            model.usages
                .filterIsInstance<PortUsage>()
                .single { it.name == name }
                .effectivePortDirection(model)

        test("derives the direction from the flow properties of the port definition") {
            direction("derived") shouldBe PortDirection.In
        }
        test("conjugation swaps in and out") {
            direction("conj") shouldBe PortDirection.Out
        }
        test("flow properties in different directions give inout") {
            direction("mixed") shouldBe PortDirection.InOut
        }
        test("no flow property and no declared direction gives null") {
            direction("plain") shouldBe null
        }
        test("a declared direction wins over the flow properties") {
            direction("declared") shouldBe PortDirection.Out
        }
    })
