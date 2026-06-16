package dev.kuml.io.emf

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlClass
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.eclipse.uml2.uml.VisibilityKind
import org.eclipse.uml2.uml.Class as EmfClass

class UmlToEmfConverterTest :
    FunSpec({

        val converter = UmlToEmfConverter()

        beforeSpec { EmfBootstrap.init() }

        fun kumlModel(vararg classes: UmlClass): KumlModel =
            KumlModel(
                root =
                    KumlDiagram(
                        id = "test-diagram",
                        name = "Test Diagram",
                        type = DiagramType.CLASS,
                        elements = classes.toList(),
                    ),
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "Test Model",
            )

        test("leeres KumlModel ergibt leeres EMF Model") {
            val emfModel = converter.convert(kumlModel())
            emfModel.ownedTypes.filterIsInstance<EmfClass>().shouldBeEmpty()
        }

        test("eine UmlClass wird korrekt konvertiert — Name und isAbstract=false") {
            val cls = UmlClass(id = "Order", name = "Order")
            val emfModel = converter.convert(kumlModel(cls))
            val emfClasses = emfModel.ownedTypes.filterIsInstance<EmfClass>()
            emfClasses shouldHaveSize 1
            emfClasses.first().name shouldBe "Order"
            emfClasses.first().isAbstract shouldBe false
        }

        test("abstrakte UmlClass wird korrekt konvertiert — isAbstract=true") {
            val cls = UmlClass(id = "BaseEntity", name = "BaseEntity", isAbstract = true)
            val emfModel = converter.convert(kumlModel(cls))
            val emfClasses = emfModel.ownedTypes.filterIsInstance<EmfClass>()
            emfClasses.first().isAbstract shouldBe true
        }

        test("Visibility PRIVATE wird korrekt gemapped") {
            val cls = UmlClass(id = "Secret", name = "Secret", visibility = Visibility.PRIVATE)
            val emfModel = converter.convert(kumlModel(cls))
            val emfClasses = emfModel.ownedTypes.filterIsInstance<EmfClass>()
            emfClasses.first().visibility shouldBe VisibilityKind.PRIVATE_LITERAL
        }
    })
