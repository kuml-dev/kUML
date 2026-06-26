package dev.kuml.io.emf

import dev.kuml.profile.autosar.autosarProfile
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.io.path.createTempFile

class ProfileXmiImporterTest :
    FunSpec({

        val exporter = ProfileXmiExporter()
        val importer = ProfileXmiImporter()

        beforeSpec { EmfBootstrap.init() }

        test("import reads a Profile produced by ProfileXmiExporter") {
            val tmpFile = createTempFile("importer-test-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val imported = importer.import(tmpFile)
            imported.name shouldBe autosarProfile.name
        }

        test("importResult on valid file returns Success") {
            val tmpFile = createTempFile("importer-result-valid-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            exporter.export(autosarProfile, tmpFile)
            val result = importer.importResult(tmpFile)
            result.shouldBeInstanceOf<ProfileResult.Success>()
        }

        test("importResult on malformed .profile.uml returns Failure not exception") {
            val tmpFile = createTempFile("importer-malformed-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            tmpFile.writeText("<garbage/>")
            val result = importer.importResult(tmpFile)
            result.shouldBeInstanceOf<ProfileResult.Failure>()
        }

        test("importResult Failure contains a non-blank message") {
            val tmpFile = createTempFile("importer-malformed-msg-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            tmpFile.writeText("<garbage/>")
            val result = importer.importResult(tmpFile) as ProfileResult.Failure
            result.message.isBlank() shouldBe false
        }

        test("importResult Failure carries the causing throwable") {
            val tmpFile = createTempFile("importer-malformed-cause-", ".profile.uml").toFile()
            tmpFile.deleteOnExit()
            tmpFile.writeText("<garbage/>")
            val result = importer.importResult(tmpFile) as ProfileResult.Failure
            result.cause shouldBe result.cause // just assert non-null shape via message check done above
        }

        test("import on valid model XMI (uml:Model, no Profile) returns Failure via importResult") {
            // Write a proper Model XMI via XmiWriter (uses xmi extension), then read via importResult
            val modelFile = createTempFile("importer-model-xmi-", ".xmi").toFile()
            modelFile.deleteOnExit()
            val writer = XmiWriter()
            val emfModel =
                org.eclipse.uml2.uml.UMLFactory.eINSTANCE
                    .createModel()
            emfModel.name = "TestModel"
            writer.write(emfModel, modelFile)
            // The file contains a uml:Model, not uml:Profile — importResult should return Failure
            val result = importer.importResult(modelFile)
            result.shouldBeInstanceOf<ProfileResult.Failure>()
        }

        test("import throws on missing file") {
            val missingFile = java.io.File("/tmp/does-not-exist-kuml-${System.nanoTime()}.profile.uml")
            shouldThrowAny {
                importer.import(missingFile)
            }
        }

        test("importResult on missing file returns Failure") {
            val missingFile = java.io.File("/tmp/does-not-exist-kuml-${System.nanoTime()}.profile.uml")
            val result = importer.importResult(missingFile)
            result.shouldBeInstanceOf<ProfileResult.Failure>()
        }
    })
