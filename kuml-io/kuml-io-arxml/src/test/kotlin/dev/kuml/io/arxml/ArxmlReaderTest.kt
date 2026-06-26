package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

private val MINIMAL_ARXML =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd">
      <AR-PACKAGES>
        <AR-PACKAGE>
          <SHORT-NAME>MyPackage</SHORT-NAME>
        </AR-PACKAGE>
      </AR-PACKAGES>
    </AUTOSAR>
    """.trimIndent()

private val SWC_ARXML =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd">
      <AR-PACKAGES>
        <AR-PACKAGE>
          <SHORT-NAME>Components</SHORT-NAME>
          <ELEMENTS>
            <APPLICATION-SW-COMPONENT-TYPE>
              <SHORT-NAME>SpeedController</SHORT-NAME>
              <PORTS>
                <P-PORT-PROTOTYPE>
                  <SHORT-NAME>SpeedOut</SHORT-NAME>
                </P-PORT-PROTOTYPE>
                <R-PORT-PROTOTYPE>
                  <SHORT-NAME>SpeedIn</SHORT-NAME>
                </R-PORT-PROTOTYPE>
              </PORTS>
            </APPLICATION-SW-COMPONENT-TYPE>
            <SENDER-RECEIVER-INTERFACE>
              <SHORT-NAME>ISpeedData</SHORT-NAME>
            </SENDER-RECEIVER-INTERFACE>
            <CLIENT-SERVER-INTERFACE>
              <SHORT-NAME>IDiagnostic</SHORT-NAME>
            </CLIENT-SERVER-INTERFACE>
          </ELEMENTS>
        </AR-PACKAGE>
      </AR-PACKAGES>
    </AUTOSAR>
    """.trimIndent()

private val NESTED_PACKAGES_ARXML =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd">
      <AR-PACKAGES>
        <AR-PACKAGE>
          <SHORT-NAME>Root</SHORT-NAME>
          <AR-PACKAGES>
            <AR-PACKAGE>
              <SHORT-NAME>Child</SHORT-NAME>
            </AR-PACKAGE>
          </AR-PACKAGES>
        </AR-PACKAGE>
      </AR-PACKAGES>
    </AUTOSAR>
    """.trimIndent()

private val R19_11_ARXML =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_00048.xsd">
      <AR-PACKAGES>
        <AR-PACKAGE>
          <SHORT-NAME>MyPackage</SHORT-NAME>
        </AR-PACKAGE>
      </AR-PACKAGES>
    </AUTOSAR>
    """.trimIndent()

private val NO_SCHEMA_LOCATION_ARXML =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
      <AR-PACKAGES>
        <AR-PACKAGE>
          <SHORT-NAME>MyPackage</SHORT-NAME>
        </AR-PACKAGE>
      </AR-PACKAGES>
    </AUTOSAR>
    """.trimIndent()

private val UNKNOWN_ELEMENT_ARXML =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd">
      <AR-PACKAGES>
        <AR-PACKAGE>
          <SHORT-NAME>Pkg</SHORT-NAME>
          <ELEMENTS>
            <SOME-UNKNOWN-FUTURE-ELEMENT>
              <SHORT-NAME>Future</SHORT-NAME>
            </SOME-UNKNOWN-FUTURE-ELEMENT>
          </ELEMENTS>
        </AR-PACKAGE>
      </AR-PACKAGES>
    </AUTOSAR>
    """.trimIndent()

class ArxmlReaderTest :
    FunSpec({

        val reader = ArxmlReader()

        test("parses minimal valid AUTOSAR with one AR-PACKAGE") {
            val result = reader.readFromString(MINIMAL_ARXML)
            result.rootPackage.name shouldBe "AUTOSAR"
            result.rootPackage.members shouldHaveSize 1
            val pkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            pkg.name shouldBe "MyPackage"
        }

        test("version auto-detected from schemaLocation as R22_11") {
            val result = reader.readFromString(MINIMAL_ARXML)
            result.version shouldBe ArxmlVersion.R22_11
        }

        test("parses APPLICATION-SW-COMPONENT-TYPE as UmlComponent with SoftwareComponent stereotype") {
            val result = reader.readFromString(SWC_ARXML)
            val pkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            val swc = pkg.members.filterIsInstance<UmlComponent>().first()
            swc.name shouldBe "SpeedController"
            swc.stereotypes shouldBe listOf("SoftwareComponent")
            (swc.metadata["kind"] as? KumlMetaValue.Text)?.value shouldBe "application"
        }

        test("parses P-PORT-PROTOTYPE as UmlPort with provided direction") {
            val result = reader.readFromString(SWC_ARXML)
            val pkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            val swc = pkg.members.filterIsInstance<UmlComponent>().first()
            val providedPort = swc.ports.first { it.name == "SpeedOut" }
            providedPort.shouldBeInstanceOf<UmlPort>()
            providedPort.stereotypes shouldBe listOf("AutosarPort")
            (providedPort.metadata["direction"] as? KumlMetaValue.Text)?.value shouldBe "provided"
        }

        test("parses R-PORT-PROTOTYPE as UmlPort with required direction") {
            val result = reader.readFromString(SWC_ARXML)
            val pkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            val swc = pkg.members.filterIsInstance<UmlComponent>().first()
            val requiredPort = swc.ports.first { it.name == "SpeedIn" }
            requiredPort.shouldBeInstanceOf<UmlPort>()
            (requiredPort.metadata["direction"] as? KumlMetaValue.Text)?.value shouldBe "required"
        }

        test("parses SENDER-RECEIVER-INTERFACE as UmlInterface with ComInterface stereotype") {
            val result = reader.readFromString(SWC_ARXML)
            val pkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            val iface = pkg.members.filterIsInstance<UmlInterface>().first { it.name == "ISpeedData" }
            iface.stereotypes shouldBe listOf("ComInterface")
        }

        test("parses CLIENT-SERVER-INTERFACE as UmlInterface with ComInterface stereotype and isService") {
            val result = reader.readFromString(SWC_ARXML)
            val pkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            val iface = pkg.members.filterIsInstance<UmlInterface>().first { it.name == "IDiagnostic" }
            iface.stereotypes shouldBe listOf("ComInterface")
            (iface.metadata["isService"] as? KumlMetaValue.Text)?.value shouldBe "true"
        }

        test("nested AR-PACKAGE maps to nested UmlPackage members") {
            val result = reader.readFromString(NESTED_PACKAGES_ARXML)
            val rootPkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            rootPkg.name shouldBe "Root"
            val child = rootPkg.members[0].shouldBeInstanceOf<UmlPackage>()
            child.name shouldBe "Child"
        }

        test("unknown AUTOSAR element produces warning not exception") {
            val result = reader.readFromString(UNKNOWN_ELEMENT_ARXML)
            result.warnings.any { it.contains("SOME-UNKNOWN-FUTURE-ELEMENT") } shouldBe true
        }

        test("readFromString and read(File) produce structurally equivalent results") {
            val tmpFile = Files.createTempFile("arxml-test-", ".arxml").toFile()
            try {
                tmpFile.writeText(SWC_ARXML)
                val fromString = reader.readFromString(SWC_ARXML)
                val fromFile = reader.read(tmpFile)

                fromString.version shouldBe fromFile.version
                fromString.rootPackage.members shouldHaveSize fromFile.rootPackage.members.size
                val pkgFromString = fromString.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
                val pkgFromFile = fromFile.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
                pkgFromString.name shouldBe pkgFromFile.name
                pkgFromString.members shouldHaveSize pkgFromFile.members.size
            } finally {
                tmpFile.delete()
            }
        }

        test("SWC package contains correct number of elements") {
            val result = reader.readFromString(SWC_ARXML)
            val pkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            // 1 SWC + 2 interfaces = 3 members
            pkg.members shouldHaveSize 3
        }

        test("parser collects no warnings for well-formed schemaLocation ARXML") {
            val result = reader.readFromString(MINIMAL_ARXML)
            result.warnings.none { it.contains("schemaLocation") } shouldBe true
        }

        test("genuine R19_11 file with AUTOSAR_00048 schemaLocation is detected as R19_11 not R22_11") {
            val result = reader.readFromString(R19_11_ARXML)
            result.version shouldBe ArxmlVersion.R19_11
            result.warnings.none { it.contains("schemaLocation") } shouldBe true
        }

        test("ARXML without xsi:schemaLocation defaults to R22_11 and emits warning") {
            val result = reader.readFromString(NO_SCHEMA_LOCATION_ARXML)
            result.version shouldBe ArxmlVersion.R22_11
            result.warnings.any { it.contains("xsi:schemaLocation absent") } shouldBe true
        }
    })
