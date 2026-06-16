package dev.kuml.io.emf

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.uml2.uml.UMLFactory

class XmiWriterTest :
    FunSpec({

        val writer = XmiWriter()
        val reader = XmiReader()

        beforeSpec { EmfBootstrap.init() }

        fun buildModel(
            name: String = "TestModel",
            block: org.eclipse.uml2.uml.Model.() -> Unit = {},
        ) = UMLFactory.eINSTANCE.createModel().also {
            it.name = name
            it.block()
        }

        test("writeToString produziert valides XML mit XML-Deklaration") {
            val model = buildModel("SimpleModel")
            val xml = writer.writeToString(model)
            xml shouldContain "<?xml"
        }

        test("writeToString enthält Modellnamen") {
            val model = buildModel("OrderDomain")
            val xml = writer.writeToString(model)
            xml shouldContain "OrderDomain"
        }

        test("writeToString enthält Klassenname der hinzugefügten Klasse") {
            val model = buildModel("Domain")
            model.createOwnedClass("Product", false)
            val xml = writer.writeToString(model)
            xml shouldContain "Product"
        }

        test("Roundtrip: writeToString → readFromString → gleiches Modell") {
            val model = buildModel("RoundtripModel")
            model.createOwnedClass("Invoice", false)
            model.createOwnedClass("LineItem", true)

            val xml = writer.writeToString(model)
            val readBack = reader.readFromString(xml)

            readBack.name shouldBe "RoundtripModel"
            val classes = readBack.packagedElements.filterIsInstance<org.eclipse.uml2.uml.Class>()
            classes.size shouldBe 2
            classes.map { it.name }.toSet() shouldBe setOf("Invoice", "LineItem")
        }

        test("leeres Modell writeToString wirft keine Exception") {
            val model = buildModel("EmptyModel")
            shouldNotThrowAny {
                writer.writeToString(model)
            }
        }
    })
