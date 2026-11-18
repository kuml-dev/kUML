package dev.kuml.cli.workspace

import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.DeploymentDiagram
import dev.kuml.c4.model.DynamicDiagram
import dev.kuml.c4.model.SystemLandscapeDiagram
import dev.kuml.core.script.ExtractedDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OkfTypeMapperTest :
    FunSpec({
        val model = C4Model(id = "m", name = "M")

        fun typeOf(diagram: C4Diagram): String? = OkfTypeMapper.toOkfTypeId(ExtractedDiagram.C4(model = model, diagram = diagram))

        test("C4 landscape, deployment and dynamic diagrams map to their vocabulary 1.1 types") {
            typeOf(SystemLandscapeDiagram(id = "l", name = "L")) shouldBe "C4LandscapeDiagram"
            typeOf(DeploymentDiagram(id = "d", name = "D")) shouldBe "C4DeploymentDiagram"
            typeOf(DynamicDiagram(id = "y", name = "Y")) shouldBe "C4DynamicDiagram"
        }
    })
