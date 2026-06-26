package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.sysml2.PartDefinition
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlPackage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Adaptive Platform manifest round-trip tests.
 *
 * Tests that SERVICE-MANIFEST and MACHINE-MANIFEST elements survive export→re-import.
 * The export uses [ArxmlClassicExporter] with `emitAdaptiveManifests=true`;
 * the import uses [ArxmlAdaptiveImporter].
 *
 * V3.1.36 — initial implementation.
 */
class ArxmlAdaptiveManifestRoundtripTest :
    FunSpec({
        val adaptiveImporter = ArxmlAdaptiveImporter()

        test("adaptive: minimal service manifest survives export→re-import") {
            // Build a Classic model with a service manifest component (kind=Manifest, manifestKind=service)
            val manifestComp =
                UmlComponent(
                    id = "comp-manifest",
                    name = "SpeedServiceManifest",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata =
                        mapOf(
                            "kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_MANIFEST),
                            "manifestKind" to KumlMetaValue.Text("service"),
                        ),
                )
            val pkg =
                UmlPackage(
                    id = "pkg-manifests",
                    name = "Manifests",
                    members = listOf(manifestComp),
                )
            val root =
                UmlPackage(
                    id = "root",
                    name = "AUTOSAR",
                    members = listOf(pkg),
                )
            val model =
                KumlModel(
                    root = root,
                    language = ModelingLanguage.UML,
                    level = ModelLevel.PIM,
                    name = "ServiceManifestTest",
                )

            val exporter = ArxmlClassicExporter(version = ArxmlVersion.R22_11, emitAdaptiveManifests = true)
            val xml = exporter.export(model)

            // Verify the exported XML contains SERVICE-MANIFEST
            xml shouldContain ArxmlSchema.ELEM_SERVICE_MANIFEST
            xml shouldContain "SpeedServiceManifest"

            // Re-import via AdaptiveImporter
            val result = adaptiveImporter.importFromString(xml)
            result.unresolved.shouldBeEmpty()

            // The Sysml2Model should contain a PartDefinition for the manifest
            val sysml2Model = result.model
            sysml2Model.definitions.isNotEmpty() shouldBe true
            val manifestDef =
                sysml2Model.definitions
                    .filterIsInstance<PartDefinition>()
                    .firstOrNull { it.name == "SpeedServiceManifest" }
            manifestDef?.name shouldBe "SpeedServiceManifest"
        }

        test("adaptive: machine manifest survives export→re-import") {
            val machineManifestComp =
                UmlComponent(
                    id = "comp-machine-manifest",
                    name = "BrakeECUMachineManifest",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata =
                        mapOf(
                            "kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_MANIFEST),
                            "manifestKind" to KumlMetaValue.Text("machine"),
                        ),
                )
            val pkg =
                UmlPackage(
                    id = "pkg-machine",
                    name = "MachineManifests",
                    members = listOf(machineManifestComp),
                )
            val root =
                UmlPackage(
                    id = "root",
                    name = "AUTOSAR",
                    members = listOf(pkg),
                )
            val model =
                KumlModel(
                    root = root,
                    language = ModelingLanguage.UML,
                    level = ModelLevel.PIM,
                    name = "MachineManifestTest",
                )

            val exporter = ArxmlClassicExporter(version = ArxmlVersion.R22_11, emitAdaptiveManifests = true)
            val xml = exporter.export(model)

            xml shouldContain ArxmlSchema.ELEM_MACHINE_MANIFEST
            xml shouldContain "BrakeECUMachineManifest"

            val result = adaptiveImporter.importFromString(xml)
            result.unresolved.shouldBeEmpty()

            val sysml2Model = result.model
            val manifestDef =
                sysml2Model.definitions
                    .filterIsInstance<PartDefinition>()
                    .firstOrNull { it.name == "BrakeECUMachineManifest" }
            manifestDef?.name shouldBe "BrakeECUMachineManifest"
        }

        test("adaptive: mixed classic and service instance in one document") {
            // A package with a classic SWC alongside a service instance
            val classicComp =
                UmlComponent(
                    id = "comp-classic",
                    name = "SpeedSensorSwc",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                )
            val serviceInstanceComp =
                UmlComponent(
                    id = "comp-si",
                    name = "SpeedServiceInstance",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_SERVICE_INSTANCE)),
                )
            val pkg =
                UmlPackage(
                    id = "pkg-mixed",
                    name = "Mixed",
                    members = listOf(classicComp, serviceInstanceComp),
                )
            val root =
                UmlPackage(
                    id = "root",
                    name = "AUTOSAR",
                    members = listOf(pkg),
                )
            val model =
                KumlModel(
                    root = root,
                    language = ModelingLanguage.UML,
                    level = ModelLevel.PIM,
                    name = "MixedTest",
                )

            val exporter = ArxmlClassicExporter(version = ArxmlVersion.R22_11, emitAdaptiveManifests = true)
            val xml = exporter.export(model)

            xml shouldContain ArxmlSchema.ELEM_APPLICATION_SWC
            xml shouldContain "SpeedSensorSwc"
            xml shouldContain ArxmlSchema.ELEM_SERVICE_INSTANCE
            xml shouldContain "SpeedServiceInstance"

            // Classic importer: picks up the SpeedSensorSwc, the SERVICE-INSTANCE is unknown→warning
            val classicImporter = ArxmlClassicImporter()
            val classicResult = classicImporter.importFromString(xml)
            val classicRoot = classicResult.model.root as UmlPackage
            val classicPkg = classicRoot.members.filterIsInstance<UmlPackage>().first { it.name == "Mixed" }
            val recoveredComponents = classicPkg.members.filterIsInstance<UmlComponent>()
            // The classic importer only recovers classic SWC elements
            recoveredComponents.any { it.name == "SpeedSensorSwc" } shouldBe true
        }
    })
