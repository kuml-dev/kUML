package dev.kuml.io.emf

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInterface
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain

class XmiExporterTest :
    FunSpec({

        val exporter = XmiExporter()
        val importer = XmiImporter()
        val reader = XmiReader()
        val writer = XmiWriter()

        beforeSpec { EmfBootstrap.init() }

        fun kumlModel(
            name: String = "ExportModel",
            vararg elements: dev.kuml.core.model.KumlElement,
        ): KumlModel =
            KumlModel(
                root =
                    KumlDiagram(
                        id = "export-diagram",
                        name = name,
                        type = DiagramType.CLASS,
                        elements = elements.toList(),
                    ),
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = name,
            )

        fun resourceFile(name: String): java.io.File {
            val url =
                checkNotNull(
                    XmiExporterTest::class.java.classLoader.getResource("xmi-samples/$name"),
                ) { "Test resource xmi-samples/$name not found on classpath" }
            return java.io.File(url.toURI())
        }

        test("Export KumlModel mit UmlClass produziert XMI mit Klassenname") {
            val model = kumlModel("OrderModel", UmlClass(id = "Order", name = "Order"))
            val tmpFile =
                kotlin.io.path
                    .createTempFile("kuml-export-test-", ".xmi")
                    .toFile()
            tmpFile.deleteOnExit()
            exporter.export(model, tmpFile)
            val xml = tmpFile.readText()
            xml shouldContain "Order"
        }

        test("Export KumlModel mit UmlInterface produziert XMI mit Interface-Element") {
            val model = kumlModel("InterfaceModel", UmlInterface(id = "Printable", name = "Printable"))
            val tmpFile =
                kotlin.io.path
                    .createTempFile("kuml-export-iface-", ".xmi")
                    .toFile()
            tmpFile.deleteOnExit()
            exporter.export(model, tmpFile)
            val xml = tmpFile.readText()
            xml shouldContain "Printable"
        }

        test("Roundtrip: XmiImporter.import → XmiExporter.export → XmiImporter.import → gleiche Klassenanzahl") {
            val originalFile = resourceFile("papyrus-minimal.xmi")
            val firstImport = importer.import(originalFile)

            val tmpFile =
                kotlin.io.path
                    .createTempFile("kuml-roundtrip-", ".xmi")
                    .toFile()
            tmpFile.deleteOnExit()
            exporter.export(firstImport, tmpFile)

            val secondImport = importer.import(tmpFile)
            val firstDiagram = firstImport.root as dev.kuml.core.model.KumlDiagram
            val secondDiagram = secondImport.root as dev.kuml.core.model.KumlDiagram

            val firstClasses = firstDiagram.elements.filterIsInstance<UmlClass>()
            val secondClasses = secondDiagram.elements.filterIsInstance<UmlClass>()
            secondClasses shouldHaveSize firstClasses.size
        }

        test("Leeres KumlModel exportiert ohne Exception") {
            val model = kumlModel("EmptyModel")
            val tmpFile =
                kotlin.io.path
                    .createTempFile("kuml-empty-export-", ".xmi")
                    .toFile()
            tmpFile.deleteOnExit()
            shouldNotThrowAny {
                exporter.export(model, tmpFile)
            }
        }
    })
