package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlPackage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [ArxmlAdaptiveImporter] — verifies mapping of Adaptive Platform ARXML elements
 * to SysML 2 [PartDefinition]s.
 *
 * V3.1.35 — initial implementation.
 */
class ArxmlAdaptiveImporterTest :
    StringSpec({

        val importer = ArxmlAdaptiveImporter(version = ArxmlAdaptiveVersion.R23_11)

        fun adaptiveXml(elementsXml: String) =
            """
            <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_AP_00052.xsd">
              <AR-PACKAGES>
                <AR-PACKAGE>
                  <SHORT-NAME>AdaptivePkg</SHORT-NAME>
                  <ELEMENTS>
                    $elementsXml
                  </ELEMENTS>
                </AR-PACKAGE>
              </AR-PACKAGES>
            </AUTOSAR>
            """.trimIndent()

        "SERVICE-INSTANCE imports as PartDefinition with kind=ServiceInstance" {
            val xml =
                adaptiveXml(
                    """
                    <SERVICE-INSTANCE>
                      <SHORT-NAME>MyService</SHORT-NAME>
                    </SERVICE-INSTANCE>
                    """,
                )
            val result = importer.importFromString(xml)
            val defs = result.model.definitions.filterIsInstance<PartDefinition>()
            defs shouldHaveSize 1
            val def = defs.first()
            def.name shouldBe "MyService"
            (def.metadata["kind"] as? KumlMetaValue.Text)?.value shouldBe ArxmlSchema.STEREOTYPE_SERVICE_INSTANCE
            (def.metadata["stereotype"] as? KumlMetaValue.Text)?.value shouldBe ArxmlSchema.STEREOTYPE_SERVICE_INSTANCE
        }

        "ADAPTIVE-APPLICATION-SW-COMPONENT-TYPE imports as PartDefinition kind=AdaptiveApplication" {
            val xml =
                adaptiveXml(
                    """
                    <ADAPTIVE-APPLICATION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>AdaptiveApp</SHORT-NAME>
                    </ADAPTIVE-APPLICATION-SW-COMPONENT-TYPE>
                    """,
                )
            val result = importer.importFromString(xml)
            val defs = result.model.definitions.filterIsInstance<PartDefinition>()
            defs shouldHaveSize 1
            val def = defs.first()
            def.name shouldBe "AdaptiveApp"
            (def.metadata["kind"] as? KumlMetaValue.Text)?.value shouldBe ArxmlSchema.STEREOTYPE_ADAPTIVE_APPLICATION
        }

        "MACHINE-DESIGN imports as PartDefinition kind=Machine" {
            val xml =
                adaptiveXml(
                    """
                    <MACHINE-DESIGN>
                      <SHORT-NAME>ECU1</SHORT-NAME>
                    </MACHINE-DESIGN>
                    """,
                )
            val result = importer.importFromString(xml)
            val defs = result.model.definitions.filterIsInstance<PartDefinition>()
            defs shouldHaveSize 1
            val def = defs.first()
            def.name shouldBe "ECU1"
            (def.metadata["kind"] as? KumlMetaValue.Text)?.value shouldBe ArxmlSchema.STEREOTYPE_MACHINE
        }

        "SERVICE-MANIFEST imports as a manifest PartDefinition with leaf values as KermlFeatures" {
            val xml =
                adaptiveXml(
                    """
                    <SERVICE-MANIFEST>
                      <SHORT-NAME>ServiceManifest1</SHORT-NAME>
                      <INSTANCE-IDENTIFIER>42</INSTANCE-IDENTIFIER>
                      <SD-COLLECTION-TRIGGER>ALWAYS</SD-COLLECTION-TRIGGER>
                    </SERVICE-MANIFEST>
                    """,
                )
            val result = importer.importFromString(xml)
            val defs = result.model.definitions.filterIsInstance<PartDefinition>()
            defs shouldHaveSize 1
            val def = defs.first()
            def.name shouldBe "ServiceManifest1"
            (def.metadata["kind"] as? KumlMetaValue.Text)?.value shouldBe ArxmlSchema.STEREOTYPE_MANIFEST
            (def.metadata["manifestKind"] as? KumlMetaValue.Text)?.value shouldBe "service"
            // Leaf values present as KermlFeatures
            def.features.any { it.name == "INSTANCE-IDENTIFIER" }.shouldBeTrue()
        }

        "MACHINE-MANIFEST imports as manifest PartDefinition with manifestKind=machine" {
            val xml =
                adaptiveXml(
                    """
                    <MACHINE-MANIFEST>
                      <SHORT-NAME>MachineManifest1</SHORT-NAME>
                      <TRUST-MASTER-SECRET>secret123</TRUST-MASTER-SECRET>
                    </MACHINE-MANIFEST>
                    """,
                )
            val result = importer.importFromString(xml)
            val defs = result.model.definitions.filterIsInstance<PartDefinition>()
            defs shouldHaveSize 1
            val def = defs.first()
            def.name shouldBe "MachineManifest1"
            (def.metadata["manifestKind"] as? KumlMetaValue.Text)?.value shouldBe "machine"
        }

        "BdDiagram lists all imported definition ids" {
            val xml =
                adaptiveXml(
                    """
                    <SERVICE-INSTANCE>
                      <SHORT-NAME>Svc1</SHORT-NAME>
                    </SERVICE-INSTANCE>
                    <MACHINE-DESIGN>
                      <SHORT-NAME>Machine1</SHORT-NAME>
                    </MACHINE-DESIGN>
                    """,
                )
            val result = importer.importFromString(xml)
            val diagram =
                result.model.diagrams
                    .filterIsInstance<BdDiagram>()
                    .firstOrNull()
            diagram.shouldNotBeNull()
            val defIds = result.model.definitions.map { it.id }
            diagram.elementIds.containsAll(defIds).shouldBeTrue()
        }

        "partial input (missing SHORT-NAME) produces a warning, does not throw" {
            val xml =
                adaptiveXml(
                    """
                    <SERVICE-INSTANCE>
                    </SERVICE-INSTANCE>
                    """,
                )
            val result = importer.importFromString(xml)
            // Should not throw — partial input results in a placeholder name + warning
            result.model.definitions shouldHaveSize 1
            result.warnings.any { it.contains("SHORT-NAME") }.shouldBeTrue()
        }

        "unknown Adaptive element produces a warning and does not throw" {
            val xml =
                adaptiveXml(
                    """
                    <UNKNOWN-ELEMENT-XYZ>
                      <SHORT-NAME>Xyz</SHORT-NAME>
                    </UNKNOWN-ELEMENT-XYZ>
                    """,
                )
            val result = importer.importFromString(xml)
            // Unknown element is warned, no definitions produced for it
            result.warnings.any { it.contains("UNKNOWN-ELEMENT-XYZ") }.shouldBeTrue()
        }

        "version is set on result from constructor override" {
            val fixedImporter = ArxmlAdaptiveImporter(version = ArxmlAdaptiveVersion.R21_11)
            val xml =
                adaptiveXml(
                    """
                    <MACHINE-DESIGN>
                      <SHORT-NAME>M1</SHORT-NAME>
                    </MACHINE-DESIGN>
                    """,
                )
            val result = fixedImporter.importFromString(xml)
            result.version shouldBe ArxmlAdaptiveVersion.R21_11
        }

        "kumlModel root package contains UmlComponent for every imported definition" {
            val xml =
                adaptiveXml(
                    """
                    <SERVICE-INSTANCE>
                      <SHORT-NAME>SvcA</SHORT-NAME>
                    </SERVICE-INSTANCE>
                    <MACHINE-DESIGN>
                      <SHORT-NAME>MachB</SHORT-NAME>
                    </MACHINE-DESIGN>
                    """,
                )
            val result = importer.importFromString(xml)
            val rootPkg = result.kumlModel.root as? UmlPackage
            rootPkg.shouldNotBeNull()
            val members = rootPkg.members
            members shouldHaveSize 2
            val svc = members.filterIsInstance<UmlComponent>().first { it.name == "SvcA" }
            (svc.metadata["kind"] as? KumlMetaValue.Text)?.value shouldBe ArxmlSchema.STEREOTYPE_SERVICE_INSTANCE
            val mach = members.filterIsInstance<UmlComponent>().first { it.name == "MachB" }
            (mach.metadata["kind"] as? KumlMetaValue.Text)?.value shouldBe ArxmlSchema.STEREOTYPE_MACHINE
        }
    })
