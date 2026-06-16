package dev.kuml.io.emf

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.eclipse.uml2.uml.UMLFactory
import org.eclipse.uml2.uml.VisibilityKind

class EmfToUmlConverterTest :
    FunSpec({

        val converter = EmfToUmlConverter()

        beforeSpec { EmfBootstrap.init() }

        fun emfModel(name: String = "TestModel") = UMLFactory.eINSTANCE.createModel().also { it.name = name }

        test("leeres EMF Model ergibt KumlModel ohne Klassen") {
            val emfModel = emfModel()
            val kumlModel = converter.convert(emfModel)
            val diagram = kumlModel.root as KumlDiagram
            diagram.elements.filterIsInstance<UmlClass>().shouldBeEmpty()
        }

        test("eine EMF Class wird korrekt konvertiert — Name und isAbstract=false") {
            val emfModel = emfModel()
            emfModel.createOwnedClass("Customer", false)
            val kumlModel = converter.convert(emfModel)
            val diagram = kumlModel.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            classes shouldHaveSize 1
            classes.first().name shouldBe "Customer"
            classes.first().isAbstract shouldBe false
        }

        test("abstrakte EMF Class wird korrekt konvertiert — isAbstract=true") {
            val emfModel = emfModel()
            emfModel.createOwnedClass("AbstractBase", true)
            val kumlModel = converter.convert(emfModel)
            val diagram = kumlModel.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            classes.first().isAbstract shouldBe true
        }

        test("Visibility PUBLIC wird gemapped") {
            val emfModel = emfModel()
            val emfClass = emfModel.createOwnedClass("PublicClass", false)
            emfClass.visibility = VisibilityKind.PUBLIC_LITERAL
            val kumlModel = converter.convert(emfModel)
            val diagram = kumlModel.root as KumlDiagram
            diagram.elements
                .filterIsInstance<UmlClass>()
                .first()
                .visibility shouldBe Visibility.PUBLIC
        }

        test("Visibility PRIVATE wird gemapped") {
            val emfModel = emfModel()
            val emfClass = emfModel.createOwnedClass("PrivateClass", false)
            emfClass.visibility = VisibilityKind.PRIVATE_LITERAL
            val kumlModel = converter.convert(emfModel)
            val diagram = kumlModel.root as KumlDiagram
            diagram.elements
                .filterIsInstance<UmlClass>()
                .first()
                .visibility shouldBe Visibility.PRIVATE
        }
    })
