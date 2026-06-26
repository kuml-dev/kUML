package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private fun buildModel(
    version: ArxmlVersion,
    vararg packages: UmlPackage,
): KumlModel {
    val root =
        UmlPackage(
            id = "root",
            name = "AUTOSAR",
            members = packages.toList(),
        )
    return KumlModel(root = root, language = ModelingLanguage.UML, level = ModelLevel.PIM, name = "Test")
}

class ArxmlClassicExporterTest :
    FunSpec({

        val exporter = ArxmlClassicExporter(version = ArxmlVersion.R22_11)

        test("export of UmlComponent emits COMPOSITION/APPLICATION-SW-COMPONENT-TYPE by kind") {
            val compositionComp =
                UmlComponent(
                    id = "c1",
                    name = "CompositionSWC",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("composition")),
                )
            val applicationComp =
                UmlComponent(
                    id = "c2",
                    name = "ApplicationSWC",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                )
            val pkg = UmlPackage(id = "p1", name = "Comps", members = listOf(compositionComp, applicationComp))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, pkg))
            xml shouldContain ArxmlSchema.ELEM_COMPOSITION_SWC
            xml shouldContain ArxmlSchema.ELEM_APPLICATION_SWC
            xml shouldContain "CompositionSWC"
            xml shouldContain "ApplicationSWC"
        }

        test("port direction provided/required emits P/R-PORT-PROTOTYPE") {
            val pPort =
                UmlPort(
                    id = "port1",
                    name = "BrakeOut",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
                    metadata = mapOf("direction" to KumlMetaValue.Text("provided")),
                )
            val rPort =
                UmlPort(
                    id = "port2",
                    name = "SpeedIn",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
                    metadata = mapOf("direction" to KumlMetaValue.Text("required")),
                )
            val comp =
                UmlComponent(
                    id = "c1",
                    name = "BrakeCtrl",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("composition")),
                    ports = listOf(pPort, rPort),
                )
            val pkg = UmlPackage(id = "p1", name = "Comps", members = listOf(comp))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, pkg))
            xml shouldContain ArxmlSchema.ELEM_P_PORT_PROTOTYPE
            xml shouldContain ArxmlSchema.ELEM_R_PORT_PROTOTYPE
            xml shouldContain "BrakeOut"
            xml shouldContain "SpeedIn"
        }

        test("port interface ref emits REQUIRED/PROVIDED-INTERFACE-TREF with AUTOSAR path") {
            val pPort =
                UmlPort(
                    id = "port1",
                    name = "BrakeOut",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
                    metadata =
                        mapOf(
                            "direction" to KumlMetaValue.Text("provided"),
                            "interfaceRef" to KumlMetaValue.Text("/Interfaces/IBrake"),
                        ),
                )
            val comp =
                UmlComponent(
                    id = "c1",
                    name = "BrakeCtrl",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("composition")),
                    ports = listOf(pPort),
                )
            val pkg = UmlPackage(id = "p1", name = "Comps", members = listOf(comp))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, pkg))
            xml shouldContain ArxmlSchema.ELEM_PROVIDED_INTERFACE_TREF
            xml shouldContain "/Interfaces/IBrake"
        }

        test("runnable emits RUNNABLE-ENTITY plus matching EVENTS block with START-ON-EVENT-REF") {
            val runnable =
                UmlOperation(
                    id = "op1",
                    name = "Cyclic10ms",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
                    metadata = mapOf("trigger" to KumlMetaValue.Text("TIMING")),
                )
            val comp =
                UmlComponent(
                    id = "c1",
                    name = "BrakeCtrl",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                    operations = listOf(runnable),
                )
            val pkg = UmlPackage(id = "p1", name = "Comps", members = listOf(comp))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, pkg))
            xml shouldContain ArxmlSchema.ELEM_RUNNABLE_ENTITY
            xml shouldContain "Cyclic10ms"
            xml shouldContain ArxmlSchema.ELEM_EVENTS
            xml shouldContain ArxmlSchema.ELEM_TIMING_EVENT
            xml shouldContain ArxmlSchema.ELEM_START_ON_EVENT_REF
        }

        test("BehaviorSpec in component metadata emits BEHAVIOR-SPEC element") {
            val comp =
                UmlComponent(
                    id = "c1",
                    name = "SafetyMgr",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata =
                        mapOf(
                            "kind" to KumlMetaValue.Text("application"),
                            "behaviorSpec" to KumlMetaValue.Text("SafetyStateMachine"),
                        ),
                    // Need at least one runnable to trigger INTERNAL-BEHAVIORS block
                    operations =
                        listOf(
                            UmlOperation(
                                id = "op1",
                                name = "SafetyCheck",
                                stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
                            ),
                        ),
                )
            val pkg = UmlPackage(id = "p1", name = "Comps", members = listOf(comp))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, pkg))
            xml shouldContain ArxmlSchema.ELEM_BEHAVIOR_SPEC
            xml shouldContain "SafetyStateMachine"
        }

        test("AR-PACKAGE nesting matches UmlPackage hierarchy, xmlns and xsi:schemaLocation match version") {
            val inner = UmlPackage(id = "p2", name = "InnerPkg", members = emptyList())
            val outer = UmlPackage(id = "p1", name = "OuterPkg", members = listOf(inner))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, outer))
            xml shouldContain ArxmlVersion.R22_11.namespaceUri
            xml shouldContain ArxmlVersion.R22_11.schemaLabel
            xml shouldContain "OuterPkg"
            xml shouldContain "InnerPkg"
            xml shouldNotContain "AUTOSAR_00048" // R19_11 should not appear when using R22_11
        }
    })
