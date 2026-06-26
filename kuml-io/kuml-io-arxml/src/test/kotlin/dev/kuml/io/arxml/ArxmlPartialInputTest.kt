package dev.kuml.io.arxml

import dev.kuml.uml.UmlPackage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests the 'never throws for partial input' contract of [ArxmlClassicImporter].
 *
 * Any input that is well-formed XML (even if structurally incomplete ARXML) must produce
 * an [ImportResult] rather than propagating an exception to the caller.
 *
 * V3.1.34 — initial implementation.
 */
class ArxmlPartialInputTest :
    FunSpec({

        val importer = ArxmlClassicImporter()

        test("empty ELEMENTS block returns ImportResult without exception") {
            val xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<AUTOSAR xmlns=\"http://autosar.org/schema/r4.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "         xsi:schemaLocation=\"http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd\">\n" +
                    "  <AR-PACKAGES>\n" +
                    "    <AR-PACKAGE>\n" +
                    "      <SHORT-NAME>EmptyPkg</SHORT-NAME>\n" +
                    "      <ELEMENTS/>\n" +
                    "    </AR-PACKAGE>\n" +
                    "  </AR-PACKAGES>\n" +
                    "</AUTOSAR>"
            val result = importer.importFromString(xml)
            // No exception — result must be a valid ImportResult
            result.model.root.shouldBeInstanceOf<UmlPackage>()
        }

        test("missing AR-PACKAGES element returns ImportResult with warning") {
            val xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<AUTOSAR xmlns=\"http://autosar.org/schema/r4.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "         xsi:schemaLocation=\"http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd\">\n" +
                    "</AUTOSAR>"
            val result = importer.importFromString(xml)
            result.model.root.shouldBeInstanceOf<UmlPackage>()
            val hasArPkgWarning = result.warnings.any { it.contains("AR-PACKAGES") }
            @Suppress("KotlinConstantConditions")
            assert(hasArPkgWarning) { "Expected warning about missing AR-PACKAGES but got: ${result.warnings}" }
        }

        test("unknown element FOO-BAR in ELEMENTS returns ImportResult with warning") {
            val xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<AUTOSAR xmlns=\"http://autosar.org/schema/r4.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "         xsi:schemaLocation=\"http://autosar.org/schema/r4.0 AUTOSAR_00051.xsd\">\n" +
                    "  <AR-PACKAGES>\n" +
                    "    <AR-PACKAGE>\n" +
                    "      <SHORT-NAME>TestPkg</SHORT-NAME>\n" +
                    "      <ELEMENTS>\n" +
                    "        <FOO-BAR>\n" +
                    "          <SHORT-NAME>WeirdElement</SHORT-NAME>\n" +
                    "        </FOO-BAR>\n" +
                    "      </ELEMENTS>\n" +
                    "    </AR-PACKAGE>\n" +
                    "  </AR-PACKAGES>\n" +
                    "</AUTOSAR>"
            val result = importer.importFromString(xml)
            result.model.root.shouldBeInstanceOf<UmlPackage>()
            val hasWarning = result.warnings.any { it.contains("FOO-BAR") }
            @Suppress("KotlinConstantConditions")
            assert(hasWarning) { "Expected warning about FOO-BAR but got: ${result.warnings}" }
        }
    })
