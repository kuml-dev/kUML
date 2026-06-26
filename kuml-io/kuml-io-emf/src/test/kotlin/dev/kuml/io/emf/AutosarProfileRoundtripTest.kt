package dev.kuml.io.emf

import dev.kuml.profile.autosar.autosarProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.io.path.createTempFile

class AutosarProfileRoundtripTest :
    FunSpec({

        val exporter = ProfileXmiExporter()
        val importer = ProfileXmiImporter()

        beforeSpec { EmfBootstrap.init() }

        test("autosar round-trip kUML → .profile.uml → kUML preserves all 5 stereotypes") {
            val tmpFile = createTempFile("autosar-roundtrip-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.stereotypes shouldHaveSize 5
            imported.stereotypes.map { it.name } shouldContainExactlyInAnyOrder
                listOf("SoftwareComponent", "ComInterface", "AutosarPort", "Runnable", "BehaviorSpec")
        }

        test("round-trip preserves profile name") {
            val tmpFile = createTempFile("autosar-roundtrip-name-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.name shouldBe autosarProfile.name
        }

        test("round-trip preserves profile namespace") {
            val tmpFile = createTempFile("autosar-roundtrip-ns-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.namespace shouldBe autosarProfile.namespace
        }

        test("round-trip preserves profile version") {
            val tmpFile = createTempFile("autosar-roundtrip-ver-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.version shouldBe autosarProfile.version
        }

        test("round-trip preserves profile description") {
            val tmpFile = createTempFile("autosar-roundtrip-desc-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.description shouldBe autosarProfile.description
        }

        test("round-trip preserves each stereotype targetMetaclass") {
            val tmpFile = createTempFile("autosar-roundtrip-mc-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            for (original in autosarProfile.stereotypes) {
                val roundTripped = imported.stereotype(original.name)!!
                roundTripped.targetMetaclass shouldBe original.targetMetaclass
            }
        }

        test("round-trip preserves property name sets per stereotype") {
            val tmpFile = createTempFile("autosar-roundtrip-props-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            for (original in autosarProfile.stereotypes) {
                val roundTripped = imported.stereotype(original.name)!!
                roundTripped.properties.map { it.name }.toSet() shouldBe
                    original.properties.map { it.name }.toSet()
            }
        }

        test("round-trip preserves required flags via EAnnotation") {
            val tmpFile = createTempFile("autosar-roundtrip-req-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            // SoftwareComponent.kind has a default → required=false in original
            val swc = imported.stereotype("SoftwareComponent")!!
            val kindProp = swc.properties.first { it.name == "kind" }
            kindProp.required shouldBe false
        }

        test("importResult on valid file returns Success") {
            val tmpFile = createTempFile("autosar-roundtrip-result-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val result = importer.importResult(tmpFile)
            result.shouldBeInstanceOf<ProfileResult.Success>()
        }
    })
