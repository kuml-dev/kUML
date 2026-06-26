package dev.kuml.io.emf

import dev.kuml.profile.javaee.javaEeProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempFile

class JavaEeProfileRoundtripTest :
    FunSpec({

        val exporter = ProfileXmiExporter()
        val importer = ProfileXmiImporter()

        beforeSpec { EmfBootstrap.init() }

        test("javaee round-trip preserves stereotype count") {
            val tmpFile = createTempFile("javaee-roundtrip-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(javaEeProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.stereotypes.size shouldBe javaEeProfile.stereotypes.size
        }

        test("javaee round-trip preserves stereotype names") {
            val tmpFile = createTempFile("javaee-roundtrip-names-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(javaEeProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.stereotypes.map { it.name } shouldContainExactlyInAnyOrder
                javaEeProfile.stereotypes.map { it.name }
        }

        test("javaee round-trip preserves profile namespace") {
            val tmpFile = createTempFile("javaee-roundtrip-ns-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(javaEeProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.namespace shouldBe javaEeProfile.namespace
        }

        test("javaee round-trip preserves each stereotype targetMetaclass") {
            val tmpFile = createTempFile("javaee-roundtrip-mc-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(javaEeProfile, tmpFile)
            val imported = importer.import(tmpFile)
            for (original in javaEeProfile.stereotypes) {
                val roundTripped = imported.stereotype(original.name)!!
                roundTripped.targetMetaclass shouldBe original.targetMetaclass
            }
        }
    })
