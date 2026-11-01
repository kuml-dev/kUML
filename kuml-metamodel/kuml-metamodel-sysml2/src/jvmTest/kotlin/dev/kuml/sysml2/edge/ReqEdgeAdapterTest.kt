package dev.kuml.sysml2.edge

import dev.kuml.sysml2.ReqContains
import dev.kuml.sysml2.ReqDerive
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.ReqVerify
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * V2.0.13 — verifies that [ReqEdgeAdapter] reports the four canonical SysML 2
 * traceability stereotypes (`«satisfy»`, `«verify»`, `«deriveReqt»`,
 * `«containment»` until wave 2E-a, now a crosshair), all dashed, all `OpenAngle` arrow heads.
 */
class ReqEdgeAdapterTest :
    StringSpec({

        val diagram =
            ReqDiagram(
                name = "Reqs",
                elementIds = listOf("Vehicle", "TopSpeedReq", "SafetyReq", "BrakingReq", "TopSpeedTest"),
                satisfies =
                    listOf(
                        ReqSatisfy(
                            id = "satisfy:Vehicle::TopSpeedReq",
                            sourceId = "Vehicle",
                            requirementId = "TopSpeedReq",
                        ),
                    ),
                verifies =
                    listOf(
                        ReqVerify(
                            id = "verify:TopSpeedTest::TopSpeedReq",
                            sourceId = "TopSpeedTest",
                            requirementId = "TopSpeedReq",
                        ),
                    ),
                derives =
                    listOf(
                        ReqDerive(
                            id = "derive:BrakingReq::SafetyReq",
                            sourceRequirementId = "BrakingReq",
                            targetRequirementId = "SafetyReq",
                        ),
                    ),
                contains =
                    listOf(
                        ReqContains(
                            id = "contains:SafetyReq::BrakingReq",
                            parentRequirementId = "SafetyReq",
                            childRequirementId = "BrakingReq",
                        ),
                    ),
            )
        val adapter = ReqEdgeAdapter(diagram)

        "satisfy edge — «satisfy» stereotype, dashed, open angle" {
            val meta = adapter.metadataFor("satisfy:Vehicle::TopSpeedReq")!!
            meta.stereotype shouldBe "«satisfy»"
            meta.dashArray shouldBe "5 4"
            meta.arrowHead shouldBe Sysml2ArrowHead.OpenAngle
        }

        "verify edge — «verify» stereotype, dashed, open angle" {
            val meta = adapter.metadataFor("verify:TopSpeedTest::TopSpeedReq")!!
            meta.stereotype shouldBe "«verify»"
            meta.dashArray shouldBe "5 4"
        }

        "derive edge — «deriveReqt» stereotype, dashed, open angle" {
            val meta = adapter.metadataFor("derive:BrakingReq::SafetyReq")!!
            meta.stereotype shouldBe "«deriveReqt»"
            meta.dashArray shouldBe "5 4"
        }

        "contains edge — no text label, solid line, circled plus at the parent end (wave 2E-a)" {
            val meta = adapter.metadataFor("contains:SafetyReq::BrakingReq")!!
            meta.stereotype.shouldBeNull()
            meta.label.shouldBeNull()
            meta.dashArray.shouldBeNull()
            meta.arrowHead shouldBe Sysml2ArrowHead.ContainmentCrosshair
            meta.headAtSource shouldBe true
        }

        "unknown edge id returns null" {
            adapter.metadataFor("nope").shouldBeNull()
        }
    })
