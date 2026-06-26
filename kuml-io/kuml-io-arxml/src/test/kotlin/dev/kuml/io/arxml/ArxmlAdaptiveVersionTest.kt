package dev.kuml.io.arxml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jdom2.input.SAXBuilder

/**
 * Unit tests for [ArxmlAdaptiveVersion] detection logic.
 *
 * V3.1.35 — initial implementation.
 */
class ArxmlAdaptiveVersionTest :
    FunSpec({

        fun parseRoot(xml: String) = SAXBuilder().build(xml.reader()).rootElement

        test("detect returns R23_11 from xsi:schemaLocation AUTOSAR_AP_00052 token") {
            val xml =
                """
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_AP_00052.xsd">
                  <AR-PACKAGES/>
                </AUTOSAR>
                """.trimIndent()
            val root = parseRoot(xml)
            val warnings = mutableListOf<String>()
            val result = ArxmlAdaptiveVersion.detect(root, warnings)
            result.shouldNotBeNull()
            result shouldBe ArxmlAdaptiveVersion.R23_11
        }

        test("detect returns R22_11 from xsi:schemaLocation AUTOSAR_AP_00051 token") {
            val xml =
                """
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_AP_00051.xsd">
                  <AR-PACKAGES/>
                </AUTOSAR>
                """.trimIndent()
            val root = parseRoot(xml)
            val result = ArxmlAdaptiveVersion.detect(root)
            result.shouldNotBeNull()
            result shouldBe ArxmlAdaptiveVersion.R22_11
        }

        test("detect returns null for a Classic document (no AP token, no Adaptive root children)") {
            val xml =
                """
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd">
                  <AR-PACKAGES>
                    <AR-PACKAGE>
                      <SHORT-NAME>Components</SHORT-NAME>
                      <ELEMENTS>
                        <APPLICATION-SW-COMPONENT-TYPE>
                          <SHORT-NAME>MySwc</SHORT-NAME>
                        </APPLICATION-SW-COMPONENT-TYPE>
                      </ELEMENTS>
                    </AR-PACKAGE>
                  </AR-PACKAGES>
                </AUTOSAR>
                """.trimIndent()
            val root = parseRoot(xml)
            val result = ArxmlAdaptiveVersion.detect(root)
            result.shouldBeNull()
        }

        test("isAdaptiveDocument returns true when root has SERVICE-INSTANCE child") {
            val xml =
                """
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_AP_00052.xsd">
                  <AR-PACKAGES>
                    <AR-PACKAGE>
                      <SHORT-NAME>Services</SHORT-NAME>
                      <ELEMENTS>
                        <SERVICE-INSTANCE>
                          <SHORT-NAME>MyService</SHORT-NAME>
                        </SERVICE-INSTANCE>
                      </ELEMENTS>
                    </AR-PACKAGE>
                  </AR-PACKAGES>
                </AUTOSAR>
                """.trimIndent()
            val root = parseRoot(xml)
            ArxmlAdaptiveVersion.isAdaptiveDocument(root).shouldBeTrue()
        }

        test("isAdaptiveDocument returns false for a Classic document") {
            val xml =
                """
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd">
                  <AR-PACKAGES/>
                </AUTOSAR>
                """.trimIndent()
            val root = parseRoot(xml)
            ArxmlAdaptiveVersion.isAdaptiveDocument(root).shouldBeFalse()
        }

        test("detect falls back to R23_11 with warning when AP namespace present but schemaLocation absent") {
            val xml =
                """
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0">
                  <AR-PACKAGES>
                    <AR-PACKAGE>
                      <SHORT-NAME>Machines</SHORT-NAME>
                      <ELEMENTS>
                        <MACHINE-DESIGN>
                          <SHORT-NAME>ECU1</SHORT-NAME>
                        </MACHINE-DESIGN>
                      </ELEMENTS>
                    </AR-PACKAGE>
                  </AR-PACKAGES>
                </AUTOSAR>
                """.trimIndent()
            val root = parseRoot(xml)
            val warnings = mutableListOf<String>()
            val result = ArxmlAdaptiveVersion.detect(root, warnings)
            result.shouldNotBeNull()
            result shouldBe ArxmlAdaptiveVersion.R23_11
            warnings.any { it.contains("R23_11") }.shouldBeTrue()
        }

        test("isAdaptiveSchemaLabel returns true for AP labels") {
            ArxmlSchema.isAdaptiveSchemaLabel("AUTOSAR_AP_00052.xsd").shouldBeTrue()
            ArxmlSchema.isAdaptiveSchemaLabel("AUTOSAR_AP_00051").shouldBeTrue()
        }

        test("isAdaptiveSchemaLabel returns false for Classic labels") {
            ArxmlSchema.isAdaptiveSchemaLabel("AUTOSAR_00051.xsd").shouldBeFalse()
            ArxmlSchema.isAdaptiveSchemaLabel("AUTOSAR_00052").shouldBeFalse()
        }

        test("detect returns R19_03 for AUTOSAR_AP_00047 schema label") {
            val xml =
                """
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://autosar.org/schema/r4.0 AUTOSAR_AP_00047.xsd">
                  <AR-PACKAGES/>
                </AUTOSAR>
                """.trimIndent()
            val root = parseRoot(xml)
            val result = ArxmlAdaptiveVersion.detect(root)
            result.shouldNotBeNull()
            result shouldBe ArxmlAdaptiveVersion.R19_03
        }
    })
