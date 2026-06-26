package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
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
            // Component has a behaviorSpec but zero Runnable-stereotyped operations —
            // the INTERNAL-BEHAVIORS block must still be emitted (regression test for the
            // previous bug where the block was gated solely on runnables.isNotEmpty()).
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
                )
            val pkg = UmlPackage(id = "p1", name = "Comps", members = listOf(comp))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, pkg))
            xml shouldContain ArxmlSchema.ELEM_BEHAVIOR_SPEC
            xml shouldContain "SafetyStateMachine"
            xml shouldContain ArxmlSchema.ELEM_INTERNAL_BEHAVIORS
        }

        test("BehaviorSpec is also emitted when component has both runnables and a behaviorSpec") {
            val runnable =
                UmlOperation(
                    id = "op1",
                    name = "SafetyCheck",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
                )
            val comp =
                UmlComponent(
                    id = "c1",
                    name = "SafetyMgrWithRunnable",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata =
                        mapOf(
                            "kind" to KumlMetaValue.Text("application"),
                            "behaviorSpec" to KumlMetaValue.Text("SafetyStateMachine"),
                        ),
                    operations = listOf(runnable),
                )
            val pkg = UmlPackage(id = "p1", name = "Comps", members = listOf(comp))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, pkg))
            xml shouldContain ArxmlSchema.ELEM_RUNNABLE_ENTITY
            xml shouldContain ArxmlSchema.ELEM_BEHAVIOR_SPEC
            xml shouldContain "SafetyStateMachine"
        }

        test("INTERFACE-TREF DEST attribute reflects actual interface kind — SR vs CS") {
            val srInterface =
                UmlInterface(
                    id = "i1",
                    name = "IBrake",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
                )
            val csInterface =
                UmlInterface(
                    id = "i2",
                    name = "IDiag",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
                    metadata = mapOf("isService" to KumlMetaValue.Text("true")),
                )
            val pPortSr =
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
            val pPortCs =
                UmlPort(
                    id = "port2",
                    name = "DiagOut",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
                    metadata =
                        mapOf(
                            "direction" to KumlMetaValue.Text("provided"),
                            "interfaceRef" to KumlMetaValue.Text("/Interfaces/IDiag"),
                        ),
                )
            val comp =
                UmlComponent(
                    id = "c1",
                    name = "BrakeCtrl",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                    ports = listOf(pPortSr, pPortCs),
                )
            val ifacePkg = UmlPackage(id = "p2", name = "Interfaces", members = listOf(srInterface, csInterface))
            val compPkg = UmlPackage(id = "p1", name = "Comps", members = listOf(comp))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, ifacePkg, compPkg))
            // SR interface ref must carry DEST="SENDER-RECEIVER-INTERFACE"
            xml shouldContain """DEST="${ArxmlSchema.ELEM_SENDER_RECEIVER_INTERFACE}""""
            // CS interface ref must carry DEST="CLIENT-SERVER-INTERFACE"
            xml shouldContain """DEST="${ArxmlSchema.ELEM_CLIENT_SERVER_INTERFACE}""""
        }

        test("two interfaces in different packages with identical short-names emit distinct TREFs without collision") {
            // If buildPathIndex keyed by short-name, the second "IData" would overwrite the first
            // and the port pointing to /PackageA/IData would resolve to the wrong absolute path.
            val ifaceA =
                UmlInterface(
                    id = "ia",
                    name = "IData",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
                )
            val ifaceB =
                UmlInterface(
                    id = "ib",
                    name = "IData",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
                    metadata = mapOf("isService" to KumlMetaValue.Text("true")),
                )
            val pkgA = UmlPackage(id = "pa", name = "PackageA", members = listOf(ifaceA))
            val pkgB = UmlPackage(id = "pb", name = "PackageB", members = listOf(ifaceB))
            val portA =
                UmlPort(
                    id = "po1",
                    name = "DataOutA",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
                    metadata =
                        mapOf(
                            "direction" to KumlMetaValue.Text("provided"),
                            "interfaceRef" to KumlMetaValue.Text("/PackageA/IData"),
                        ),
                )
            val portB =
                UmlPort(
                    id = "po2",
                    name = "DataOutB",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
                    metadata =
                        mapOf(
                            "direction" to KumlMetaValue.Text("provided"),
                            "interfaceRef" to KumlMetaValue.Text("/PackageB/IData"),
                        ),
                )
            val comp =
                UmlComponent(
                    id = "c1",
                    name = "SomeComp",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                    ports = listOf(portA, portB),
                )
            val compPkg = UmlPackage(id = "pc", name = "Comps", members = listOf(comp))
            val xml = exporter.export(buildModel(ArxmlVersion.R22_11, pkgA, pkgB, compPkg))
            // SR interface (PackageA/IData) must use SENDER-RECEIVER-INTERFACE DEST
            xml shouldContain """DEST="${ArxmlSchema.ELEM_SENDER_RECEIVER_INTERFACE}""""
            // CS interface (PackageB/IData, isService=true) must use CLIENT-SERVER-INTERFACE DEST
            xml shouldContain """DEST="${ArxmlSchema.ELEM_CLIENT_SERVER_INTERFACE}""""
        }

        test("emitAdaptiveManifests=true with kind=Manifest emits SERVICE-MANIFEST and MACHINE-MANIFEST elements") {
            val serviceManifest =
                UmlComponent(
                    id = "m1",
                    name = "MyServiceManifest",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_MANIFEST),
                    metadata =
                        mapOf(
                            "kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_MANIFEST),
                            "manifestKind" to KumlMetaValue.Text("service"),
                        ),
                )
            val machineManifest =
                UmlComponent(
                    id = "m2",
                    name = "MyMachineManifest",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_MANIFEST),
                    metadata =
                        mapOf(
                            "kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_MANIFEST),
                            "manifestKind" to KumlMetaValue.Text("machine"),
                        ),
                )
            val pkg = UmlPackage(id = "p1", name = "Manifests", members = listOf(serviceManifest, machineManifest))
            val adaptiveExporter = ArxmlClassicExporter(version = ArxmlVersion.R22_11, emitAdaptiveManifests = true)
            val xml = adaptiveExporter.export(buildModel(ArxmlVersion.R22_11, pkg))
            xml shouldContain ArxmlSchema.ELEM_SERVICE_MANIFEST
            xml shouldContain "MyServiceManifest"
            xml shouldContain ArxmlSchema.ELEM_MACHINE_MANIFEST
            xml shouldContain "MyMachineManifest"
            // Classic component element must NOT be emitted for manifest kinds
            xml shouldNotContain ArxmlSchema.ELEM_APPLICATION_SWC
        }

        test("emitAdaptiveManifests=true: kind=Manifest without manifestKind defaults to SERVICE-MANIFEST") {
            val manifestNoKind =
                UmlComponent(
                    id = "m3",
                    name = "DefaultManifest",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_MANIFEST),
                    metadata =
                        mapOf(
                            "kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_MANIFEST),
                            // manifestKind absent → should default to service
                        ),
                )
            val pkg = UmlPackage(id = "p1", name = "Manifests", members = listOf(manifestNoKind))
            val adaptiveExporter = ArxmlClassicExporter(version = ArxmlVersion.R22_11, emitAdaptiveManifests = true)
            val xml = adaptiveExporter.export(buildModel(ArxmlVersion.R22_11, pkg))
            xml shouldContain ArxmlSchema.ELEM_SERVICE_MANIFEST
            xml shouldContain "DefaultManifest"
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
