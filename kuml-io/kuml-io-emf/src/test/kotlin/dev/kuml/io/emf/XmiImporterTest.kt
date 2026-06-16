package dev.kuml.io.emf

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class XmiImporterTest :
    FunSpec({

        val importer = XmiImporter()

        beforeSpec { EmfBootstrap.init() }

        fun resourceFile(name: String): java.io.File {
            val url =
                checkNotNull(
                    XmiImporterTest::class.java.classLoader.getResource("xmi-samples/$name"),
                ) { "Test resource xmi-samples/$name not found on classpath" }
            return java.io.File(url.toURI())
        }

        test("Import ea-minimal.xmi ergibt KumlModel mit 2 UmlClass") {
            val model = importer.import(resourceFile("ea-minimal.xmi"))
            val diagram = model.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            classes shouldHaveSize 2
            classes.map { it.name }.toSet() shouldBe setOf("Order", "Item")
        }

        test("Import papyrus-minimal.xmi ergibt KumlModel mit UmlClass + UmlInterface + UmlEnumeration mit 2 Literals") {
            val model = importer.import(resourceFile("papyrus-minimal.xmi"))
            val diagram = model.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            val interfaces = diagram.elements.filterIsInstance<UmlInterface>()
            val enums = diagram.elements.filterIsInstance<UmlEnumeration>()
            classes shouldHaveSize 1
            classes.first().name shouldBe "Order"
            interfaces shouldHaveSize 1
            interfaces.first().name shouldBe "Orderable"
            enums shouldHaveSize 1
            enums.first().name shouldBe "OrderStatus"
            enums.first().literals shouldHaveSize 2
        }

        test("Import standard-class-diagram.xmi ergibt Klasse mit Attribut und Operation") {
            val model = importer.import(resourceFile("standard-class-diagram.xmi"))
            val diagram = model.root as KumlDiagram
            val classes = diagram.elements.filterIsInstance<UmlClass>()
            val orderClass = classes.first { it.name == "Order" }
            orderClass.attributes shouldHaveSize 1
            orderClass.attributes.first().name shouldBe "id"
            orderClass.operations shouldHaveSize 1
            orderClass.operations.first().name shouldBe "confirm"
        }

        test("XmiToolFilter bereinigt EA-Präfix im Modellnamen") {
            val model = importer.import(resourceFile("ea-minimal.xmi"))
            // EA_Model_OrderDomain → OrderDomain nach XmiToolFilter.normalize
            model.name shouldBe "OrderDomain"
        }
    })
