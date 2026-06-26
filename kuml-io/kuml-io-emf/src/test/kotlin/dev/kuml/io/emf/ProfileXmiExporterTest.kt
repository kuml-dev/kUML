package dev.kuml.io.emf

import dev.kuml.profile.autosar.autosarProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.uml2.uml.UMLPackage
import kotlin.io.path.createTempFile

class ProfileXmiExporterTest :
    FunSpec({

        val exporter = ProfileXmiExporter()

        beforeSpec { EmfBootstrap.init() }

        test("export writes a non-empty .profile.uml file") {
            val tmpFile = createTempFile("exporter-test-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            tmpFile.exists() shouldBe true
            (tmpFile.length() > 0L) shouldBe true
        }

        test("exported XMI contains uml:Profile root element") {
            val xml = exporter.writeToString(autosarProfile)
            xml shouldContain "uml:Profile"
        }

        test("exported XMI contains XML declaration") {
            val xml = exporter.writeToString(autosarProfile)
            xml shouldContain "<?xml"
        }

        test("exported XMI declares UMLPackage namespace URI") {
            val xml = exporter.writeToString(autosarProfile)
            xml shouldContain UMLPackage.eNS_URI
        }

        test("exported XMI contains all stereotype names") {
            val xml = exporter.writeToString(autosarProfile)
            xml shouldContain "SoftwareComponent"
            xml shouldContain "ComInterface"
            xml shouldContain "AutosarPort"
            xml shouldContain "Runnable"
            xml shouldContain "BehaviorSpec"
        }

        test("exported XMI contains profile name") {
            val xml = exporter.writeToString(autosarProfile)
            xml shouldContain autosarProfile.name
        }

        test("output parent dirs are created if missing") {
            val baseDir = createTempFile("profile-export-dir-", "").toFile()
            baseDir.delete()
            val nestedFile = baseDir.resolve("nested/test.profile.uml")
            try {
                exporter.export(autosarProfile, nestedFile)
                nestedFile.exists() shouldBe true
            } finally {
                nestedFile.parentFile?.deleteRecursively()
                baseDir.deleteRecursively()
            }
        }

        test("writeToString produces valid XML parseable by DocumentBuilder") {
            val xml = exporter.writeToString(autosarProfile)
            val factory =
                javax.xml.parsers.DocumentBuilderFactory
                    .newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xml.byteInputStream())
            doc.documentElement.tagName shouldContain "Profile"
        }
    })
