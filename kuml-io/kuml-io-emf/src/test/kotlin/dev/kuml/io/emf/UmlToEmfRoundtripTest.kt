package dev.kuml.io.emf

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlClass
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class UmlToEmfRoundtripTest :
    FunSpec({

        val toEmf = UmlToEmfConverter()
        val toUml = EmfToUmlConverter()

        beforeSpec { EmfBootstrap.init() }

        fun kumlModel(vararg classes: UmlClass): KumlModel =
            KumlModel(
                root =
                    KumlDiagram(
                        id = "roundtrip-diagram",
                        name = "Roundtrip Diagram",
                        type = DiagramType.CLASS,
                        elements = classes.toList(),
                    ),
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "Roundtrip Model",
            )

        test("UmlClass Roundtrip (Kotlin → EMF → Kotlin) erhält Name") {
            val original = UmlClass(id = "Product", name = "Product")
            val emfModel = toEmf.convert(kumlModel(original))
            val roundtrip = toUml.convert(emfModel)
            val diagram = roundtrip.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            classes shouldHaveSize 1
            classes.first().name shouldBe "Product"
        }

        test("UmlClass Roundtrip erhält isAbstract=true") {
            val original = UmlClass(id = "AbstractService", name = "AbstractService", isAbstract = true)
            val emfModel = toEmf.convert(kumlModel(original))
            val roundtrip = toUml.convert(emfModel)
            val diagram = roundtrip.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            classes.first().isAbstract shouldBe true
        }

        test("Roundtrip mit mehreren Klassen erhält alle Namen") {
            val cls1 = UmlClass(id = "Alpha", name = "Alpha")
            val cls2 = UmlClass(id = "Beta", name = "Beta", visibility = Visibility.PRIVATE)
            val cls3 = UmlClass(id = "Gamma", name = "Gamma", isAbstract = true)
            val emfModel = toEmf.convert(kumlModel(cls1, cls2, cls3))
            val roundtrip = toUml.convert(emfModel)
            val diagram = roundtrip.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            classes shouldHaveSize 3
            classes.map { it.name }.toSet() shouldBe setOf("Alpha", "Beta", "Gamma")
        }
    })
