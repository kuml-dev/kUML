package dev.kuml.io.arxml

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Parametrised version-matrix tests: parse a minimal ARXML snippet for each supported schema
 * release (R19_11 through R23_11) and verify correct version detection and export round-trip.
 *
 * Each snippet includes the distinguishing `xsi:schemaLocation AUTOSAR_000xx.xsd` token that
 * `ArxmlVersion.detect()` uses to disambiguate between releases — all R4.x releases share the
 * same `xmlns` URI.
 *
 * V3.1.36 — initial implementation.
 */
private fun buildMinimalArxml(version: ArxmlVersion): String {
    val ns = version.namespaceUri
    val schemaLabel = version.schemaLabel
    return (
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<AUTOSAR xmlns=\"$ns\"\n" +
            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "         xsi:schemaLocation=\"$ns $schemaLabel.xsd\">\n" +
            "  <AR-PACKAGES>\n" +
            "    <AR-PACKAGE>\n" +
            "      <SHORT-NAME>TestPkg</SHORT-NAME>\n" +
            "      <ELEMENTS>\n" +
            "        <COMPOSITION-SW-COMPONENT-TYPE>\n" +
            "          <SHORT-NAME>TestComposition</SHORT-NAME>\n" +
            "        </COMPOSITION-SW-COMPONENT-TYPE>\n" +
            "      </ELEMENTS>\n" +
            "    </AR-PACKAGE>\n" +
            "  </AR-PACKAGES>\n" +
            "</AUTOSAR>"
    )
}

class ArxmlVersionMatrixTest :
    FunSpec({
        val importer = ArxmlClassicImporter()
        val exporter = ArxmlClassicExporter()

        context("version matrix: all supported ARXML versions parse and round-trip correctly") {
            withData(
                nameFn = { "version ${it.name}" },
                ArxmlVersion.R19_11,
                ArxmlVersion.R20_11,
                ArxmlVersion.R21_11,
                ArxmlVersion.R22_11,
                ArxmlVersion.R23_11,
            ) { version ->
                val xml = buildMinimalArxml(version)

                // Import: version detected correctly
                val result = importer.importFromString(xml)
                val root = result.model.root as dev.kuml.uml.UmlPackage
                root.members.shouldHaveSize(1)
                val pkg = root.members.first() as dev.kuml.uml.UmlPackage
                pkg.name shouldBe "TestPkg"
                pkg.members.shouldHaveSize(1)
                val comp = pkg.members.first()
                comp.name shouldBe "TestComposition"

                // Re-read via ArxmlReader to verify the schema label detection
                val parseResult = ArxmlReader().readFromString(xml)
                parseResult.version shouldBe version

                // Export round-trip: the exported XML should contain the schema label for R22_11 (default exporter version)
                val exporterForVersion = ArxmlClassicExporter(version = version)
                val reExported = exporterForVersion.export(result.model)
                reExported shouldContain version.schemaLabel
            }
        }
    })
