package dev.kuml.sysml2.projection

import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class Sysml2IbdBareNameTest :
    FunSpec(body = {
        fun model(
            from: String,
            to: String,
        ) = sysml2Model(name = "M") {
            portDef(name = "Sig")
            partDef(name = "Inner") { port(name = "ein", typeId = "Sig", direction = PortDirection.In) }
            partDef(name = "Steuerung") {
                port(name = "eingang", typeId = "Sig", direction = PortDirection.In)
                part(name = "logik", typeId = "Inner")
                connect(name = "c1", typeId = "Sig", sourceEndId = from, targetEndId = to)
            }
        }

        fun projection(m: dev.kuml.sysml2.Sysml2Model) =
            sysml2IbdProjection(model = m, diagram = IbdDiagram(name = "d", ownerId = "Steuerung"))

        test("bare owner port name resolves exactly like the qualified form") {
            val bare = projection(model(from = "eingang", to = "logik.ein"))
            val qualified = projection(model(from = "Steuerung::eingang", to = "logik.ein"))
            bare.connections shouldHaveSize 1
            bare.boundaryPorts.map { it.id } shouldBe qualified.boundaryPorts.map { it.id }
            bare.showFrame shouldBe true
            bare.unresolvedEnds shouldHaveSize 0
        }

        test("an unknown end is reported instead of being dropped silently") {
            val p = projection(model(from = "gibtsNicht", to = "logik.ein"))
            p.connections shouldHaveSize 0
            p.unresolvedEnds.map { it.endpointId } shouldBe listOf("gibtsNicht")
        }

        test("an end hidden by the diagram's elementIds is not reported as unresolved") {
            val m = model(from = "eingang", to = "logik.ein")
            val filtered =
                sysml2IbdProjection(
                    model = m,
                    diagram = IbdDiagram(name = "d", ownerId = "Steuerung", elementIds = listOf("Steuerung::unrelated")),
                )
            filtered.connections shouldHaveSize 0
            filtered.unresolvedEnds shouldHaveSize 0
        }
    })
