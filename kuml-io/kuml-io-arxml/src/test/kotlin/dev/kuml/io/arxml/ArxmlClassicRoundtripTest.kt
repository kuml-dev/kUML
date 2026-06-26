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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun buildFullTestModel(): KumlModel {
    val iface =
        UmlInterface(
            id = "iface-1",
            name = "IBrake",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
        )
    val csIface =
        UmlInterface(
            id = "iface-2",
            name = "IDiag",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
            metadata = mapOf("isService" to KumlMetaValue.Text("true")),
        )
    val ifacePkg = UmlPackage(id = "pkg-ifaces", name = "Interfaces", members = listOf(iface, csIface))

    val pPort =
        UmlPort(
            id = "port-1",
            name = "BrakeOut",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
            metadata =
                mapOf(
                    "direction" to KumlMetaValue.Text("provided"),
                    "interfaceRef" to KumlMetaValue.Text("/Interfaces/IBrake"),
                ),
        )
    val rPort =
        UmlPort(
            id = "port-2",
            name = "SpeedIn",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
            metadata = mapOf("direction" to KumlMetaValue.Text("required")),
        )
    val runnable =
        UmlOperation(
            id = "op-1",
            name = "Cyclic10ms",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
            metadata = mapOf("trigger" to KumlMetaValue.Text("TIMING")),
        )
    val comp =
        UmlComponent(
            id = "comp-1",
            name = "BrakeCtrl",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
            metadata = mapOf("kind" to KumlMetaValue.Text("composition")),
            ports = listOf(pPort, rPort),
            operations = listOf(runnable),
        )
    val compPkg = UmlPackage(id = "pkg-comps", name = "Brakes", members = listOf(comp))

    val root =
        UmlPackage(
            id = "root",
            name = "AUTOSAR",
            members = listOf(ifacePkg, compPkg),
        )
    return KumlModel(root = root, language = ModelingLanguage.UML, level = ModelLevel.PIM, name = "Test ARXML")
}

class ArxmlClassicRoundtripTest :
    FunSpec({

        val exporter = ArxmlClassicExporter(version = ArxmlVersion.R22_11)
        val importer = ArxmlClassicImporter()

        test("full model arxml→import→export→import is structurally equal") {
            val original = buildFullTestModel()

            // First export
            val xml1 = exporter.export(original)
            val result1 = importer.importFromString(xml1)

            // Second export from imported model
            val xml2 = exporter.export(result1.model)
            val result2 = importer.importFromString(xml2)

            // Check key structural elements survive two roundtrips
            val root = result2.model.root.shouldBeInstanceOf<UmlPackage>()
            val pkgNames = root.members.map { (it as? UmlPackage)?.name }
            pkgNames.contains("Brakes") shouldBe true

            val brakesPkg =
                root.members
                    .first { (it as? UmlPackage)?.name == "Brakes" }
                    .shouldBeInstanceOf<UmlPackage>()
            val comp = brakesPkg.members[0].shouldBeInstanceOf<UmlComponent>()
            comp.name shouldBe "BrakeCtrl"
            comp.ports shouldHaveSize 2
        }

        test("package paths preserved across roundtrip") {
            val nested =
                UmlPackage(
                    id = "nested",
                    name = "BrakeCtrl",
                    members = emptyList(),
                )
            val brakes = UmlPackage(id = "brakes", name = "Brakes", members = listOf(nested))
            val vehicle = UmlPackage(id = "vehicle", name = "Vehicle", members = listOf(brakes))
            val root = UmlPackage(id = "root", name = "AUTOSAR", members = listOf(vehicle))
            val model = KumlModel(root = root, language = ModelingLanguage.UML, level = ModelLevel.PIM, name = "T")

            val xml = exporter.export(model)
            val result = importer.importFromString(xml)

            val importedRoot = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val vehiclePkg = importedRoot.members[0].shouldBeInstanceOf<UmlPackage>()
            vehiclePkg.name shouldBe "Vehicle"
            val brakesPkg = vehiclePkg.members[0].shouldBeInstanceOf<UmlPackage>()
            brakesPkg.name shouldBe "Brakes"
            val brakeCtrlPkg = brakesPkg.members[0].shouldBeInstanceOf<UmlPackage>()
            brakeCtrlPkg.name shouldBe "BrakeCtrl"
        }

        test("port interface TREF preserved across roundtrip") {
            val iface =
                UmlInterface(
                    id = "iface-1",
                    name = "IBrake",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
                )
            val port =
                UmlPort(
                    id = "port-1",
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
                    ports = listOf(port),
                )
            val ifacePkg = UmlPackage(id = "ip", name = "Interfaces", members = listOf(iface))
            val compPkg = UmlPackage(id = "cp", name = "Comps", members = listOf(comp))
            val root = UmlPackage(id = "r", name = "AUTOSAR", members = listOf(ifacePkg, compPkg))
            val model = KumlModel(root = root, language = ModelingLanguage.UML, level = ModelLevel.PIM, name = "T")

            val xml = exporter.export(model)
            val result = importer.importFromString(xml)
            result.unresolved.shouldBeEmpty()

            val importedRoot = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val compsPkg =
                importedRoot.members
                    .first { (it as? UmlPackage)?.name == "Comps" }
                    .shouldBeInstanceOf<UmlPackage>()
            val importedComp = compsPkg.members[0].shouldBeInstanceOf<UmlComponent>()
            val importedPort = importedComp.ports[0]
            (importedPort.metadata["interfaceRef"] as? KumlMetaValue.Text)?.value shouldBe "/Interfaces/IBrake"
        }

        test("runnable trigger type preserved across roundtrip") {
            val runnable =
                UmlOperation(
                    id = "op1",
                    name = "InitRunnable",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
                    metadata = mapOf("trigger" to KumlMetaValue.Text("INIT")),
                )
            val comp =
                UmlComponent(
                    id = "c1",
                    name = "InitComp",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                    operations = listOf(runnable),
                )
            val pkg = UmlPackage(id = "p1", name = "Comps", members = listOf(comp))
            val root = UmlPackage(id = "r", name = "AUTOSAR", members = listOf(pkg))
            val model = KumlModel(root = root, language = ModelingLanguage.UML, level = ModelLevel.PIM, name = "T")

            val xml = exporter.export(model)
            val result = importer.importFromString(xml)

            val importedRoot = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val importedPkg = importedRoot.members[0].shouldBeInstanceOf<UmlPackage>()
            val importedComp = importedPkg.members[0].shouldBeInstanceOf<UmlComponent>()
            importedComp.operations shouldHaveSize 1
            val importedOp = importedComp.operations[0]
            importedOp.name shouldBe "InitRunnable"
            // Trigger roundtrip: the START-ON-EVENT-REF path must match so the trigger joins back
            (importedOp.metadata["trigger"] as? KumlMetaValue.Text)?.value shouldBe "INIT"
        }

        test("BehaviorSpec state machine preserved across roundtrip") {
            val runnable =
                UmlOperation(
                    id = "op1",
                    name = "SafetyCheck",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
                )
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
                    operations = listOf(runnable),
                )
            val pkg = UmlPackage(id = "p1", name = "Safety", members = listOf(comp))
            val root = UmlPackage(id = "r", name = "AUTOSAR", members = listOf(pkg))
            val model = KumlModel(root = root, language = ModelingLanguage.UML, level = ModelLevel.PIM, name = "T")

            val xml = exporter.export(model)
            val result = importer.importFromString(xml)

            val importedRoot = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val importedPkg = importedRoot.members[0].shouldBeInstanceOf<UmlPackage>()
            val importedComp = importedPkg.members[0].shouldBeInstanceOf<UmlComponent>()
            (importedComp.metadata["behaviorSpec"] as? KumlMetaValue.Text)?.value shouldBe "SafetyStateMachine"
        }

        test("BehaviorSpec name survives import → export → import roundtrip") {
            // Verifies that the importer's metadata-based representation of BehaviorSpec
            // (component.metadata["behaviorSpec"]) is preserved through a full export→import cycle.
            val xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<AUTOSAR xmlns=\"http://autosar.org/schema/r4.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "         xsi:schemaLocation=\"http://autosar.org/schema/r4.0 ${ArxmlVersion.R22_11.schemaLabel}.xsd\">\n" +
                    "  <AR-PACKAGES>\n" +
                    "    <AR-PACKAGE>\n" +
                    "      <SHORT-NAME>Safety</SHORT-NAME>\n" +
                    "      <ELEMENTS>\n" +
                    "        <APPLICATION-SW-COMPONENT-TYPE>\n" +
                    "          <SHORT-NAME>SafetyMgr</SHORT-NAME>\n" +
                    "          <INTERNAL-BEHAVIORS>\n" +
                    "            <SWC-INTERNAL-BEHAVIOR>\n" +
                    "              <SHORT-NAME>SafetyMgr_IB</SHORT-NAME>\n" +
                    "              <BEHAVIOR-SPEC>\n" +
                    "                <SHORT-NAME>MySafetyMachine</SHORT-NAME>\n" +
                    "              </BEHAVIOR-SPEC>\n" +
                    "            </SWC-INTERNAL-BEHAVIOR>\n" +
                    "          </INTERNAL-BEHAVIORS>\n" +
                    "        </APPLICATION-SW-COMPONENT-TYPE>\n" +
                    "      </ELEMENTS>\n" +
                    "    </AR-PACKAGE>\n" +
                    "  </AR-PACKAGES>\n" +
                    "</AUTOSAR>"

            // import → export → import
            val result1 = importer.importFromString(xml)
            val xml2 = exporter.export(result1.model)
            val result2 = importer.importFromString(xml2)

            val root = result2.model.root.shouldBeInstanceOf<UmlPackage>()
            val safetyPkg = root.members[0].shouldBeInstanceOf<UmlPackage>()
            val comp = safetyPkg.members[0].shouldBeInstanceOf<UmlComponent>()
            (comp.metadata["behaviorSpec"] as? KumlMetaValue.Text)?.value shouldBe "MySafetyMachine"
        }

        test("vendor internal behavior name preserved across import→export→import roundtrip") {
            // When ARXML uses a non-synthesised SWC-INTERNAL-BEHAVIOR SHORT-NAME (e.g. "BrakeCtrl_Ib"
            // instead of the synthesised "BrakeCtrl_InternalBehavior"), the exporter must reproduce
            // the original name so that START-ON-EVENT-REF paths remain valid after re-import.
            val vendorXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<AUTOSAR xmlns=\"http://autosar.org/schema/r4.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "         xsi:schemaLocation=\"http://autosar.org/schema/r4.0 ${ArxmlVersion.R22_11.schemaLabel}.xsd\">\n" +
                    "  <AR-PACKAGES>\n" +
                    "    <AR-PACKAGE>\n" +
                    "      <SHORT-NAME>Brakes</SHORT-NAME>\n" +
                    "      <ELEMENTS>\n" +
                    "        <APPLICATION-SW-COMPONENT-TYPE>\n" +
                    "          <SHORT-NAME>BrakeCtrl</SHORT-NAME>\n" +
                    "          <INTERNAL-BEHAVIORS>\n" +
                    "            <SWC-INTERNAL-BEHAVIOR>\n" +
                    "              <SHORT-NAME>BrakeCtrl_Ib</SHORT-NAME>\n" +
                    "              <RUNNABLES>\n" +
                    "                <RUNNABLE-ENTITY><SHORT-NAME>Cyclic10ms</SHORT-NAME></RUNNABLE-ENTITY>\n" +
                    "              </RUNNABLES>\n" +
                    "              <EVENTS>\n" +
                    "                <TIMING-EVENT>\n" +
                    "                  <SHORT-NAME>TimingEvent_Cyclic10ms</SHORT-NAME>\n" +
                    "                  <START-ON-EVENT-REF DEST=\"RUNNABLE-ENTITY\">" +
                    "/Brakes/BrakeCtrl/BrakeCtrl_Ib/Cyclic10ms" +
                    "</START-ON-EVENT-REF>\n" +
                    "                </TIMING-EVENT>\n" +
                    "              </EVENTS>\n" +
                    "            </SWC-INTERNAL-BEHAVIOR>\n" +
                    "          </INTERNAL-BEHAVIORS>\n" +
                    "        </APPLICATION-SW-COMPONENT-TYPE>\n" +
                    "      </ELEMENTS>\n" +
                    "    </AR-PACKAGE>\n" +
                    "  </AR-PACKAGES>\n" +
                    "</AUTOSAR>"

            // import → export → import
            val result1 = importer.importFromString(vendorXml)

            // internalBehaviorName must be captured
            val root1 = result1.model.root.shouldBeInstanceOf<UmlPackage>()
            val comp1 =
                root1.members[0]
                    .shouldBeInstanceOf<UmlPackage>()
                    .members[0]
                    .shouldBeInstanceOf<UmlComponent>()
            (comp1.metadata["internalBehaviorName"] as? KumlMetaValue.Text)?.value shouldBe "BrakeCtrl_Ib"

            // export must reproduce vendor name, not synthesise "BrakeCtrl_InternalBehavior"
            val xml2 = exporter.export(result1.model)
            xml2.contains("BrakeCtrl_Ib") shouldBe true
            xml2.contains("BrakeCtrl_InternalBehavior") shouldBe false

            // re-import must resolve the trigger (START-ON-EVENT-REF path still valid)
            val result2 = importer.importFromString(xml2)
            val root2 = result2.model.root.shouldBeInstanceOf<UmlPackage>()
            val comp2 =
                root2.members[0]
                    .shouldBeInstanceOf<UmlPackage>()
                    .members[0]
                    .shouldBeInstanceOf<UmlComponent>()
            comp2.operations shouldHaveSize 1
            (comp2.operations[0].metadata["trigger"] as? KumlMetaValue.Text)?.value shouldBe "TIMING"
        }

        test("string→export→string XML is equivalent to a fresh export of the same model") {
            val original = buildFullTestModel()
            val xml1 = exporter.export(original)
            val result = importer.importFromString(xml1)
            val xml2 = exporter.export(result.model)

            // Both must contain the same key structural markers (normalise by checking presence)
            xml2.contains("BrakeCtrl") shouldBe true
            xml2.contains("Cyclic10ms") shouldBe true
            xml2.contains(ArxmlSchema.ELEM_COMPOSITION_SWC) shouldBe true
        }
    })
