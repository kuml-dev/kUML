package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.jdom2.input.SAXBuilder
import org.jdom2.input.sax.XMLReaders
import java.io.StringReader

private fun minimalPackage(): UmlPackage {
    val swc =
        UmlComponent(
            id = "comp-1",
            name = "SpeedController",
            stereotypes = listOf("SoftwareComponent"),
            metadata = mapOf("kind" to KumlMetaValue.Text("application")),
            ports =
                listOf(
                    UmlPort(
                        id = "port-1",
                        name = "SpeedOut",
                        stereotypes = listOf("AutosarPort"),
                        metadata = mapOf("direction" to KumlMetaValue.Text("provided")),
                    ),
                    UmlPort(
                        id = "port-2",
                        name = "SpeedIn",
                        stereotypes = listOf("AutosarPort"),
                        metadata = mapOf("direction" to KumlMetaValue.Text("required")),
                    ),
                ),
        )
    val iface =
        UmlInterface(
            id = "iface-1",
            name = "ISpeedData",
            stereotypes = listOf("ComInterface"),
        )
    val innerPkg =
        UmlPackage(
            id = "pkg-1",
            name = "Components",
            members = listOf(swc, iface),
        )
    return UmlPackage(
        id = "root-1",
        name = "AUTOSAR",
        members = listOf(innerPkg),
    )
}

class ArxmlWriterTest :
    FunSpec({

        val writer = ArxmlWriter(version = ArxmlVersion.R22_11)

        test("writes well-formed XML that is re-parseable by secure SAXBuilder") {
            val xml = writer.write(minimalPackage())
            // If the SAXBuilder parses without exception, the XML is well-formed
            val sb = SAXBuilder(XMLReaders.NONVALIDATING)
            sb.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val doc = sb.build(StringReader(xml))
            doc.rootElement.name shouldBe "AUTOSAR"
        }

        test("root AUTOSAR element carries correct xmlns for version R22_11") {
            val xml = writer.write(minimalPackage())
            xml shouldContain "http://autosar.org/schema/r4.0"
        }

        test("root AUTOSAR element carries xsi:schemaLocation for version R22_11") {
            val xml = writer.write(minimalPackage())
            xml shouldContain "AUTOSAR_00051.xsd"
        }

        test("UmlComponent with kind=application maps to APPLICATION-SW-COMPONENT-TYPE with SHORT-NAME") {
            val xml = writer.write(minimalPackage())
            xml shouldContain "APPLICATION-SW-COMPONENT-TYPE"
            xml shouldContain "<SHORT-NAME>SpeedController</SHORT-NAME>"
        }

        test("UmlPort with direction=provided maps to P-PORT-PROTOTYPE") {
            val xml = writer.write(minimalPackage())
            xml shouldContain "P-PORT-PROTOTYPE"
            xml shouldContain "<SHORT-NAME>SpeedOut</SHORT-NAME>"
        }

        test("UmlPort with direction=required maps to R-PORT-PROTOTYPE") {
            val xml = writer.write(minimalPackage())
            xml shouldContain "R-PORT-PROTOTYPE"
            xml shouldContain "<SHORT-NAME>SpeedIn</SHORT-NAME>"
        }

        test("UmlInterface without isService maps to SENDER-RECEIVER-INTERFACE") {
            val xml = writer.write(minimalPackage())
            xml shouldContain "SENDER-RECEIVER-INTERFACE"
            xml shouldContain "<SHORT-NAME>ISpeedData</SHORT-NAME>"
        }

        test("output is pretty-printed with newlines and indentation") {
            val xml = writer.write(minimalPackage())
            xml.shouldNotBeBlank()
            (xml.lines().size > 5) shouldBe true
            xml shouldContain "  "
        }

        test("write to File produces same content as write to String") {
            val root = minimalPackage()
            val xmlString = writer.write(root)
            val tmpFile =
                kotlin.io.path
                    .createTempFile("arxml-writer-", ".arxml")
                    .toFile()
            try {
                writer.write(root, tmpFile)
                tmpFile.readText() shouldBe xmlString
            } finally {
                tmpFile.delete()
            }
        }
    })
