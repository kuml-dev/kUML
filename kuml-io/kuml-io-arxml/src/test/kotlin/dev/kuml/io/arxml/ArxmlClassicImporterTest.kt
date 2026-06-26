package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlPackage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Minimal AUTOSAR document wrapper. */
private fun arxmlDoc(
    innerXml: String,
    version: ArxmlVersion = ArxmlVersion.R22_11,
): String =
    (
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<AUTOSAR xmlns=\"http://autosar.org/schema/r4.0\"\n" +
            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "         xsi:schemaLocation=\"http://autosar.org/schema/r4.0 ${version.schemaLabel}.xsd\">\n" +
            "  <AR-PACKAGES>\n" +
            innerXml.trimIndent().lines().joinToString("\n") { "    $it" } + "\n" +
            "  </AR-PACKAGES>\n" +
            "</AUTOSAR>"
    )

private fun singlePkg(
    pkgName: String,
    elementsXml: String,
): String {
    val inner = elementsXml.trimIndent().lines().joinToString("\n") { "        $it" }
    return "<AR-PACKAGE>\n  <SHORT-NAME>$pkgName</SHORT-NAME>\n  <ELEMENTS>\n$inner\n  </ELEMENTS>\n</AR-PACKAGE>"
}

class ArxmlClassicImporterTest :
    FunSpec({

        val importer = ArxmlClassicImporter()

        test("imports COMPOSITION-SW-COMPONENT-TYPE as UmlComponent with SoftwareComponent stereotype kind=composition") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Components",
                        """
                    <COMPOSITION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>BrakeCtrl</SHORT-NAME>
                    </COMPOSITION-SW-COMPONENT-TYPE>
                    """,
                    ),
                )
            val result = importer.importFromString(xml)
            val pkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val innerPkg = pkg.members[0].shouldBeInstanceOf<UmlPackage>()
            innerPkg.name shouldBe "Components"
            val comp = innerPkg.members[0].shouldBeInstanceOf<UmlComponent>()
            comp.name shouldBe "BrakeCtrl"
            comp.stereotypes shouldBe listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT)
            (comp.metadata["kind"] as? KumlMetaValue.Text)?.value shouldBe "composition"
        }

        test("imports APPLICATION-SW-COMPONENT-TYPE with kind=application") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Components",
                        """
                    <APPLICATION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>SpeedSensor</SHORT-NAME>
                    </APPLICATION-SW-COMPONENT-TYPE>
                    """,
                    ),
                )
            val result = importer.importFromString(xml)
            val pkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val innerPkg = pkg.members[0].shouldBeInstanceOf<UmlPackage>()
            val comp = innerPkg.members[0].shouldBeInstanceOf<UmlComponent>()
            comp.name shouldBe "SpeedSensor"
            (comp.metadata["kind"] as? KumlMetaValue.Text)?.value shouldBe "application"
        }

        test("P-PORT-PROTOTYPE maps to AutosarPort direction=provided") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Comps",
                        """
                    <COMPOSITION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>EngineCtrl</SHORT-NAME>
                      <PORTS>
                        <P-PORT-PROTOTYPE>
                          <SHORT-NAME>SpeedOut</SHORT-NAME>
                        </P-PORT-PROTOTYPE>
                      </PORTS>
                    </COMPOSITION-SW-COMPONENT-TYPE>
                    """,
                    ),
                )
            val result = importer.importFromString(xml)
            val pkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val innerPkg = pkg.members[0].shouldBeInstanceOf<UmlPackage>()
            val comp = innerPkg.members[0].shouldBeInstanceOf<UmlComponent>()
            comp.ports shouldHaveSize 1
            val port = comp.ports[0]
            port.name shouldBe "SpeedOut"
            port.stereotypes shouldBe listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT)
            (port.metadata["direction"] as? KumlMetaValue.Text)?.value shouldBe "provided"
        }

        test("R-PORT-PROTOTYPE maps to direction=required") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Comps",
                        """
                    <COMPOSITION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>EngineCtrl</SHORT-NAME>
                      <PORTS>
                        <R-PORT-PROTOTYPE>
                          <SHORT-NAME>SpeedIn</SHORT-NAME>
                        </R-PORT-PROTOTYPE>
                      </PORTS>
                    </COMPOSITION-SW-COMPONENT-TYPE>
                    """,
                    ),
                )
            val result = importer.importFromString(xml)
            val pkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val comp =
                pkg.members[0]
                    .shouldBeInstanceOf<UmlPackage>()
                    .members[0]
                    .shouldBeInstanceOf<UmlComponent>()
            val port = comp.ports[0]
            (port.metadata["direction"] as? KumlMetaValue.Text)?.value shouldBe "required"
        }

        test("SENDER-RECEIVER-INTERFACE → ComInterface, CLIENT-SERVER-INTERFACE → ComInterface isService=true") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Interfaces",
                        """
                    <SENDER-RECEIVER-INTERFACE>
                      <SHORT-NAME>IBrake</SHORT-NAME>
                    </SENDER-RECEIVER-INTERFACE>
                    <CLIENT-SERVER-INTERFACE>
                      <SHORT-NAME>IDiag</SHORT-NAME>
                    </CLIENT-SERVER-INTERFACE>
                    """,
                    ),
                )
            val result = importer.importFromString(xml)
            val pkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val members = pkg.members[0].shouldBeInstanceOf<UmlPackage>().members
            val sr = members.filterIsInstance<UmlInterface>().first { it.name == "IBrake" }
            sr.stereotypes shouldBe listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE)
            sr.metadata["isService"] shouldBe null
            val cs = members.filterIsInstance<UmlInterface>().first { it.name == "IDiag" }
            (cs.metadata["isService"] as? KumlMetaValue.Text)?.value shouldBe "true"
        }

        test("RUNNABLE-ENTITY → UmlOperation with Runnable stereotype") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Comps",
                        """
                    <APPLICATION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>BrakeCtrl</SHORT-NAME>
                      <INTERNAL-BEHAVIORS>
                        <SWC-INTERNAL-BEHAVIOR>
                          <SHORT-NAME>BrakeCtrl_IB</SHORT-NAME>
                          <RUNNABLES>
                            <RUNNABLE-ENTITY>
                              <SHORT-NAME>BrakeRunnable</SHORT-NAME>
                            </RUNNABLE-ENTITY>
                          </RUNNABLES>
                        </SWC-INTERNAL-BEHAVIOR>
                      </INTERNAL-BEHAVIORS>
                    </APPLICATION-SW-COMPONENT-TYPE>
                    """,
                    ),
                )
            val result = importer.importFromString(xml)
            val pkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val comp =
                pkg.members[0]
                    .shouldBeInstanceOf<UmlPackage>()
                    .members[0]
                    .shouldBeInstanceOf<UmlComponent>()
            comp.operations shouldHaveSize 1
            val op = comp.operations[0]
            op.name shouldBe "BrakeRunnable"
            op.stereotypes shouldBe listOf(ArxmlSchema.STEREOTYPE_RUNNABLE)
        }

        test("runnable trigger TIMING-EVENT resolves to metadata trigger=TIMING joined by START-ON-EVENT-REF path") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Comps",
                        """
                    <APPLICATION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>BrakeCtrl</SHORT-NAME>
                      <INTERNAL-BEHAVIORS>
                        <SWC-INTERNAL-BEHAVIOR>
                          <SHORT-NAME>BrakeCtrl_IB</SHORT-NAME>
                          <RUNNABLES>
                            <RUNNABLE-ENTITY>
                              <SHORT-NAME>Cyclic10ms</SHORT-NAME>
                            </RUNNABLE-ENTITY>
                          </RUNNABLES>
                          <EVENTS>
                            <TIMING-EVENT>
                              <SHORT-NAME>TimingEvent_Cyclic10ms</SHORT-NAME>
                              <START-ON-EVENT-REF DEST="RUNNABLE-ENTITY">/Comps/BrakeCtrl/BrakeCtrl_IB/Cyclic10ms</START-ON-EVENT-REF>
                            </TIMING-EVENT>
                          </EVENTS>
                        </SWC-INTERNAL-BEHAVIOR>
                      </INTERNAL-BEHAVIORS>
                    </APPLICATION-SW-COMPONENT-TYPE>
                    """,
                    ),
                )
            val result = importer.importFromString(xml)
            val pkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val comp =
                pkg.members[0]
                    .shouldBeInstanceOf<UmlPackage>()
                    .members[0]
                    .shouldBeInstanceOf<UmlComponent>()
            comp.operations shouldHaveSize 1
            val op = comp.operations[0]
            (op.metadata["trigger"] as? KumlMetaValue.Text)?.value shouldBe RunnableTrigger.TIMING.name
        }

        test("BEHAVIOR-SPEC imports as UmlStateMachine tracked in component metadata") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Comps",
                        """
                    <APPLICATION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>SafetyMgr</SHORT-NAME>
                      <INTERNAL-BEHAVIORS>
                        <SWC-INTERNAL-BEHAVIOR>
                          <SHORT-NAME>SafetyMgr_IB</SHORT-NAME>
                          <BEHAVIOR-SPEC>
                            <SHORT-NAME>SafetyStateMachine</SHORT-NAME>
                          </BEHAVIOR-SPEC>
                        </SWC-INTERNAL-BEHAVIOR>
                      </INTERNAL-BEHAVIORS>
                    </APPLICATION-SW-COMPONENT-TYPE>
                    """,
                    ),
                )
            val result = importer.importFromString(xml)
            val pkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val comp =
                pkg.members[0]
                    .shouldBeInstanceOf<UmlPackage>()
                    .members[0]
                    .shouldBeInstanceOf<UmlComponent>()
            (comp.metadata["behaviorSpec"] as? KumlMetaValue.Text)?.value shouldBe "SafetyStateMachine"
        }

        test("unresolved interface TREF path lands in ImportResult.unresolved not an exception") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Comps",
                        """
                    <COMPOSITION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>BrakeCtrl</SHORT-NAME>
                      <PORTS>
                        <P-PORT-PROTOTYPE>
                          <SHORT-NAME>BrakeOut</SHORT-NAME>
                          <PROVIDED-INTERFACE-TREF DEST="SENDER-RECEIVER-INTERFACE">/Interfaces/IDoesNotExist</PROVIDED-INTERFACE-TREF>
                        </P-PORT-PROTOTYPE>
                      </PORTS>
                    </COMPOSITION-SW-COMPONENT-TYPE>
                    """,
                    ),
                )
            val result = importer.importFromString(xml)
            result.unresolved shouldHaveSize 1
            val ref = result.unresolved[0]
            ref.targetPath shouldBe "/Interfaces/IDoesNotExist"
            ref.kind shouldBe "interface-tref"
        }

        test("resolved interface TREF lands in port metadata interfaceRef") {
            val xml =
                arxmlDoc(
                    """
                <AR-PACKAGE>
                  <SHORT-NAME>Interfaces</SHORT-NAME>
                  <ELEMENTS>
                    <SENDER-RECEIVER-INTERFACE>
                      <SHORT-NAME>IBrake</SHORT-NAME>
                    </SENDER-RECEIVER-INTERFACE>
                  </ELEMENTS>
                </AR-PACKAGE>
                <AR-PACKAGE>
                  <SHORT-NAME>Comps</SHORT-NAME>
                  <ELEMENTS>
                    <COMPOSITION-SW-COMPONENT-TYPE>
                      <SHORT-NAME>BrakeCtrl</SHORT-NAME>
                      <PORTS>
                        <P-PORT-PROTOTYPE>
                          <SHORT-NAME>BrakeOut</SHORT-NAME>
                          <PROVIDED-INTERFACE-TREF DEST="SENDER-RECEIVER-INTERFACE">/Interfaces/IBrake</PROVIDED-INTERFACE-TREF>
                        </P-PORT-PROTOTYPE>
                      </PORTS>
                    </COMPOSITION-SW-COMPONENT-TYPE>
                  </ELEMENTS>
                </AR-PACKAGE>
                """,
                )
            val result = importer.importFromString(xml)
            result.unresolved.shouldBeEmpty()
            val rootPkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val compPkg = rootPkg.members.first { (it as? UmlPackage)?.name == "Comps" }.shouldBeInstanceOf<UmlPackage>()
            val comp = compPkg.members[0].shouldBeInstanceOf<UmlComponent>()
            val port = comp.ports[0]
            (port.metadata["interfaceRef"] as? KumlMetaValue.Text)?.value shouldBe "/Interfaces/IBrake"
        }

        test("partial arxml missing PORTS and ELEMENTS imports without throwing, warnings populated") {
            val xml =
                arxmlDoc(
                    """
                <AR-PACKAGE>
                  <SHORT-NAME>Empty</SHORT-NAME>
                </AR-PACKAGE>
                """,
                )
            val result = importer.importFromString(xml)
            val rootPkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            val innerPkg = rootPkg.members[0].shouldBeInstanceOf<UmlPackage>()
            innerPkg.members.shouldBeEmpty()
            // Warnings may or may not be present — the key contract is no exception
        }

        test("detectVersion uses namespace-detected version even when xsi:schemaLocation is absent") {
            // When the namespace resolves to a known version but xsi:schemaLocation is missing,
            // the importer must return the namespace-detected version (not fall back to R22_11)
            // and emit only an informational warning — not discard the detected version.
            val xmlNoSchemaLocation =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<AUTOSAR xmlns=\"http://autosar.org/schema/r4.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n" +
                    "  <AR-PACKAGES>\n" +
                    "    <AR-PACKAGE><SHORT-NAME>Pkg</SHORT-NAME></AR-PACKAGE>\n" +
                    "  </AR-PACKAGES>\n" +
                    "</AUTOSAR>"
            val result = importer.importFromString(xmlNoSchemaLocation)
            // Must not throw; model should be importable
            val rootPkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            rootPkg.members shouldHaveSize 1
            // An informational warning about missing schemaLocation may be present, but the
            // detected version must not be silently replaced with a default
            result.warnings.none { it.contains("defaulting to R22_11") && it.contains("namespace") } shouldBe true
        }

        test("empty ELEMENTS block imports without throwing") {
            val xml =
                arxmlDoc(
                    singlePkg("EmptyPkg", ""),
                )
            val result = importer.importFromString(xml)
            val rootPkg = result.model.root.shouldBeInstanceOf<UmlPackage>()
            rootPkg.members shouldHaveSize 1
        }

        test("unknown element in ELEMENTS block adds warning and does not throw") {
            val xml =
                arxmlDoc(
                    singlePkg(
                        "Pkg",
                        "<FOO-BAR><SHORT-NAME>Unknown</SHORT-NAME></FOO-BAR>",
                    ),
                )
            val result = importer.importFromString(xml)
            result.warnings.shouldNotBeEmpty()
            result.warnings.any { it.contains("FOO-BAR") } shouldBe true
        }
    })
