package io.kuml.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.maps.shouldBeEmpty as mapShouldBeEmpty

class KumlModelTest : FunSpec({

    test("KumlModel wraps a root element with language, level and name") {
        val root = KumlDiagram(name = "Root Diagram")
        val model =
            KumlModel(
                root = root,
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "My Model",
            )

        model.root shouldBe root
        model.language shouldBe ModelingLanguage.UML
        model.level shouldBe ModelLevel.PIM
        model.name shouldBe "My Model"
    }

    test("KumlModel metadata is empty by default") {
        val model =
            KumlModel(
                root = KumlDiagram(name = "Root"),
                language = ModelingLanguage.C4,
                level = ModelLevel.CIM,
                name = "Empty Metadata",
            )
        model.metadata.mapShouldBeEmpty()
    }

    test("ModelingLanguage has exactly UML, SYSML2, C4, MIXED") {
        ModelingLanguage.entries.shouldContainExactlyInAnyOrder(
            ModelingLanguage.UML,
            ModelingLanguage.SYSML2,
            ModelingLanguage.C4,
            ModelingLanguage.MIXED,
        )
    }

    test("ModelLevel has exactly CIM, PIM, PSM, DEPLOYMENT") {
        ModelLevel.entries.shouldContainExactlyInAnyOrder(
            ModelLevel.CIM,
            ModelLevel.PIM,
            ModelLevel.PSM,
            ModelLevel.DEPLOYMENT,
        )
    }

    test("KumlDiagram is a KumlNamespaceMember and KumlElement") {
        val diagram = KumlDiagram(name = "Test")
        diagram.shouldBeInstanceOf<KumlNamespaceMember>()
        diagram.shouldBeInstanceOf<KumlElement>()
    }

    test("KumlDiagram id defaults to its name") {
        val diagram = KumlDiagram(name = "Default Id")
        diagram.id shouldBe "Default Id"
    }

    test("KumlDiagram metadata is empty by default") {
        val diagram = KumlDiagram(name = "No Metadata")
        diagram.metadata.mapShouldBeEmpty()
    }

    test("KumlDiagram defaults to CLASS type") {
        val diagram = KumlDiagram(name = "Default")
        diagram.type shouldBe DiagramType.CLASS
    }

    test("KumlDiagram elements are empty by default") {
        val diagram = KumlDiagram(name = "Empty")
        diagram.elements.shouldBeEmpty()
    }
})
