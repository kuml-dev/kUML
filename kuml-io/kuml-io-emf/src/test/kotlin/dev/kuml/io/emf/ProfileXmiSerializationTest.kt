package dev.kuml.io.emf

import dev.kuml.profile.autosar.autosarProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl
import org.eclipse.uml2.uml.Profile
import org.eclipse.uml2.uml.UMLPackage
import kotlin.io.path.createTempFile

class ProfileXmiSerializationTest :
    FunSpec({

        val exporter = ProfileXmiExporter()
        val importer = ProfileXmiImporter()

        beforeSpec { EmfBootstrap.init() }

        test("serialized .profile.uml is valid XML parseable by a generic DocumentBuilder") {
            val xml = exporter.writeToString(autosarProfile)
            val factory =
                javax.xml.parsers.DocumentBuilderFactory
                    .newInstance()
            val builder = factory.newDocumentBuilder()
            // Should not throw
            val doc = builder.parse(xml.byteInputStream())
            doc.documentElement.tagName shouldContain "Profile"
        }

        test("re-reading exported file via raw EMF ResourceSet yields a Profile instance") {
            val tmpFile = createTempFile("serial-smoke-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)

            // Direct EMF ResourceSet read — Eclipse UML2 compatibility smoke test
            val resourceSet = ResourceSetImpl()
            resourceSet.packageRegistry[UMLPackage.eNS_URI] = UMLPackage.eINSTANCE
            resourceSet.resourceFactoryRegistry.extensionToFactoryMap["uml"] = XMIResourceFactoryImpl()
            resourceSet.resourceFactoryRegistry.extensionToFactoryMap["*"] = XMIResourceFactoryImpl()

            val resource = resourceSet.getResource(URI.createFileURI(tmpFile.absolutePath), true)
            val topLevel = resource.contents.firstOrNull()
            topLevel.shouldBeInstanceOf<Profile>()
        }

        test("exported XML contains namespace annotation source") {
            val xml = exporter.writeToString(autosarProfile)
            xml shouldContain KumlProfileToEmfConverter.PROFILE_ANNOTATION_SOURCE
        }

        test("exported XML contains metaclass sentinel prefix") {
            val xml = exporter.writeToString(autosarProfile)
            xml shouldContain KumlProfileToEmfConverter.METACLASS_ATTR_PREFIX
        }

        test("profile namespace is embedded in the XML") {
            val xml = exporter.writeToString(autosarProfile)
            xml shouldContain autosarProfile.namespace
        }

        test("full round-trip via file produces identical stereotype count") {
            val tmpFile = createTempFile("serial-rt-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.stereotypes.size shouldBe autosarProfile.stereotypes.size
        }
    })
