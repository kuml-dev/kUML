package dev.kuml.sysml2.projection

import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class Sysml2IbdUntypedPartTest :
    FunSpec(body = {
        test("a part whose type is missing or not a part def is not drawn as an IBD node") {
            val m =
                sysml2Model(name = "M") {
                    portDef(name = "Sig")
                    partDef(name = "Inner")
                    partDef(name = "Outer") {
                        part(name = "good", typeId = "Inner")
                        part(name = "missing", typeId = "Missing")
                        part(name = "wrongKind", typeId = "Sig")
                    }
                }
            val p = sysml2IbdProjection(model = m, diagram = IbdDiagram(name = "d", ownerId = "Outer"))
            p.partNodes.map { it.id.substringAfterLast("::") } shouldBe listOf("good")
        }
    })
