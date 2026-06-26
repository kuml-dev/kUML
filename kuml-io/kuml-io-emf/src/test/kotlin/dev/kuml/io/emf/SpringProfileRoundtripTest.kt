package dev.kuml.io.emf

import dev.kuml.profile.spring.springProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempFile

class SpringProfileRoundtripTest :
    FunSpec({

        val exporter = ProfileXmiExporter()
        val importer = ProfileXmiImporter()

        beforeSpec { EmfBootstrap.init() }

        test("spring round-trip preserves stereotype count") {
            val tmpFile = createTempFile("spring-roundtrip-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(springProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.stereotypes.size shouldBe springProfile.stereotypes.size
        }

        test("spring round-trip preserves stereotype names") {
            val tmpFile = createTempFile("spring-roundtrip-names-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(springProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.stereotypes.map { it.name } shouldContainExactlyInAnyOrder
                springProfile.stereotypes.map { it.name }
        }

        test("spring round-trip preserves profile namespace") {
            val tmpFile = createTempFile("spring-roundtrip-ns-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(springProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.namespace shouldBe springProfile.namespace
        }

        test("spring round-trip preserves each stereotype targetMetaclass") {
            val tmpFile = createTempFile("spring-roundtrip-mc-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(springProfile, tmpFile)
            val imported = importer.import(tmpFile)
            for (original in springProfile.stereotypes) {
                val roundTripped = imported.stereotype(original.name)!!
                roundTripped.targetMetaclass shouldBe original.targetMetaclass
            }
        }
    })
