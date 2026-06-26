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
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Comprehensive AUTOSAR Classic composition fixture:
 * - 3 SWCs (SpeedSensorSwc, BrakeControllerSwc, DiagSwc)
 * - 6 ports total (2 per SWC: 1 provided + 1 required)
 * - 2 interfaces (ISpeed sender-receiver, IDiag client-server)
 * - 4 runnables total (across all 3 SWCs, triggered by TIMING or DATA_RECEIVED)
 *
 * Tests: import→export→re-import structural equivalence (5 tests).
 *
 * V3.1.36 — initial implementation.
 */
@Suppress("LongMethod")
private fun buildClassicComposition(): KumlModel {
    // ── Interfaces ─────────────────────────────────────────────────────────
    val iSpeed =
        UmlInterface(
            id = "iface-speed",
            name = "ISpeed",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
            metadata = emptyMap(), // SENDER-RECEIVER (default)
        )
    val iDiag =
        UmlInterface(
            id = "iface-diag",
            name = "IDiag",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
            metadata = mapOf("isService" to KumlMetaValue.Text("true")), // CLIENT-SERVER
        )
    val ifacesPkg =
        UmlPackage(
            id = "pkg-ifaces",
            name = "Interfaces",
            members = listOf(iSpeed, iDiag),
        )

    // ── SpeedSensorSwc ────────────────────────────────────────────────────
    val speedSensorPort =
        UmlPort(
            id = "port-speed-p",
            name = "SpeedOut",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
            metadata =
                mapOf(
                    "direction" to KumlMetaValue.Text("provided"),
                    "interfaceRef" to KumlMetaValue.Text("/Interfaces/ISpeed"),
                ),
        )
    val speedSensorPortR =
        UmlPort(
            id = "port-speed-r",
            name = "DiagIn",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
            metadata =
                mapOf(
                    "direction" to KumlMetaValue.Text("required"),
                    "interfaceRef" to KumlMetaValue.Text("/Interfaces/IDiag"),
                ),
        )
    val speedRunnable1 =
        UmlOperation(
            id = "op-speed-1",
            name = "ReadSensor",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
            metadata = mapOf("trigger" to KumlMetaValue.Text("TIMING")),
        )
    val speedRunnable2 =
        UmlOperation(
            id = "op-speed-2",
            name = "SendSpeed",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
            metadata = mapOf("trigger" to KumlMetaValue.Text("TIMING")),
        )
    val speedSensorSwc =
        UmlComponent(
            id = "comp-speed",
            name = "SpeedSensorSwc",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
            metadata = mapOf("kind" to KumlMetaValue.Text("application")),
            ports = listOf(speedSensorPort, speedSensorPortR),
            operations = listOf(speedRunnable1, speedRunnable2),
        )

    // ── BrakeControllerSwc ─────────────────────────────────────────────────
    val brakePortR =
        UmlPort(
            id = "port-brake-r",
            name = "SpeedIn",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
            metadata =
                mapOf(
                    "direction" to KumlMetaValue.Text("required"),
                    "interfaceRef" to KumlMetaValue.Text("/Interfaces/ISpeed"),
                ),
        )
    val brakePortP =
        UmlPort(
            id = "port-brake-p",
            name = "DiagOut",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
            metadata =
                mapOf(
                    "direction" to KumlMetaValue.Text("provided"),
                    "interfaceRef" to KumlMetaValue.Text("/Interfaces/IDiag"),
                ),
        )
    val brakeRunnable =
        UmlOperation(
            id = "op-brake-1",
            name = "CalculateBrake",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
            metadata = mapOf("trigger" to KumlMetaValue.Text("DATA_RECEIVED")),
        )
    val brakeControllerSwc =
        UmlComponent(
            id = "comp-brake",
            name = "BrakeControllerSwc",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
            metadata = mapOf("kind" to KumlMetaValue.Text("application")),
            ports = listOf(brakePortR, brakePortP),
            operations = listOf(brakeRunnable),
        )

    // ── DiagSwc ─────────────────────────────────────────────────────────────
    val diagPortR =
        UmlPort(
            id = "port-diag-r",
            name = "DiagIn",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
            metadata =
                mapOf(
                    "direction" to KumlMetaValue.Text("required"),
                    "interfaceRef" to KumlMetaValue.Text("/Interfaces/IDiag"),
                ),
        )
    val diagPortP =
        UmlPort(
            id = "port-diag-p",
            name = "DiagOut",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
            metadata =
                mapOf(
                    "direction" to KumlMetaValue.Text("provided"),
                    "interfaceRef" to KumlMetaValue.Text("/Interfaces/IDiag"),
                ),
        )
    val diagRunnable =
        UmlOperation(
            id = "op-diag-1",
            name = "ProcessDiag",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
            metadata = mapOf("trigger" to KumlMetaValue.Text("TIMING")),
        )
    val diagSwc =
        UmlComponent(
            id = "comp-diag",
            name = "DiagSwc",
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
            metadata = mapOf("kind" to KumlMetaValue.Text("application")),
            ports = listOf(diagPortR, diagPortP),
            operations = listOf(diagRunnable),
        )

    val compsPkg =
        UmlPackage(
            id = "pkg-comps",
            name = "Components",
            members = listOf(speedSensorSwc, brakeControllerSwc, diagSwc),
        )

    val root =
        UmlPackage(
            id = "root",
            name = "AUTOSAR",
            members = listOf(ifacesPkg, compsPkg),
        )
    return KumlModel(root = root, language = ModelingLanguage.UML, level = ModelLevel.PIM, name = "CompositionTest")
}

class ArxmlCompositionRoundtripTest :
    FunSpec({
        val exporter = ArxmlClassicExporter(version = ArxmlVersion.R22_11)
        val importer = ArxmlClassicImporter()

        test("composition: import←export←import is structurally equal") {
            val original = buildClassicComposition()

            val xml1 = exporter.export(original)
            val result1 = importer.importFromString(xml1)
            val xml2 = exporter.export(result1.model)
            val result2 = importer.importFromString(xml2)

            val root = result2.model.root.shouldBeInstanceOf<UmlPackage>()
            // Should have 2 top-level packages: Interfaces and Components
            root.members.shouldHaveSize(2)

            val pkgNames = root.members.map { (it as? UmlPackage)?.name }
            pkgNames.contains("Interfaces") shouldBe true
            pkgNames.contains("Components") shouldBe true

            val ifacesPkg = root.members.filterIsInstance<UmlPackage>().first { it.name == "Interfaces" }
            ifacesPkg.members.shouldHaveSize(2)
            val ifaceNames = ifacesPkg.members.map { it.name }.toSet()
            ifaceNames.contains("ISpeed") shouldBe true
            ifaceNames.contains("IDiag") shouldBe true

            val compsPkg = root.members.filterIsInstance<UmlPackage>().first { it.name == "Components" }
            compsPkg.members.shouldHaveSize(3)
            val compNames = compsPkg.members.map { it.name }.toSet()
            compNames.contains("SpeedSensorSwc") shouldBe true
            compNames.contains("BrakeControllerSwc") shouldBe true
            compNames.contains("DiagSwc") shouldBe true

            // Verify each SWC has 2 ports
            val components = compsPkg.members.filterIsInstance<UmlComponent>()
            components.forEach { comp ->
                comp.ports.shouldHaveSize(2)
                comp.stereotypes.contains(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT) shouldBe true
            }

            // Verify total runnables: 2 + 1 + 1 = 4
            val totalRunnables = components.sumOf { it.operations.count { op -> ArxmlSchema.STEREOTYPE_RUNNABLE in op.stereotypes } }
            totalRunnables shouldBe 4

            // Verify IDiag is a client-server interface (has isService=true)
            val iDiag = ifacesPkg.members.first { it.name == "IDiag" }
            iDiag.shouldBeInstanceOf<dev.kuml.uml.UmlInterface>()
            (iDiag as dev.kuml.uml.UmlInterface).metadata["isService"].shouldBeInstanceOf<KumlMetaValue.Text>()
        }

        test("composition: port interface TREFs resolve with zero unresolved refs") {
            val model = buildClassicComposition()
            val xml = exporter.export(model)
            val result = importer.importFromString(xml)
            result.unresolved.shouldBeEmpty()
        }

        test("composition: runnable trigger types are preserved") {
            val model = buildClassicComposition()
            val xml = exporter.export(model)
            val result = importer.importFromString(xml)

            val root = result.model.root as UmlPackage
            val compsPkg = root.members.filterIsInstance<UmlPackage>().first { it.name == "Components" }
            val speedSensorSwc = compsPkg.members.filterIsInstance<UmlComponent>().first { it.name == "SpeedSensorSwc" }
            val runnables = speedSensorSwc.operations.filter { ArxmlSchema.STEREOTYPE_RUNNABLE in it.stereotypes }
            runnables.shouldHaveSize(2)
            val triggers = runnables.mapNotNull { (it.metadata["trigger"] as? KumlMetaValue.Text)?.value }.toSet()
            triggers.contains("TIMING") shouldBe true

            val brakeSwc = compsPkg.members.filterIsInstance<UmlComponent>().first { it.name == "BrakeControllerSwc" }
            val brakeRunnables = brakeSwc.operations.filter { ArxmlSchema.STEREOTYPE_RUNNABLE in it.stereotypes }
            brakeRunnables.shouldHaveSize(1)
            val brakeTrigger = (brakeRunnables.first().metadata["trigger"] as? KumlMetaValue.Text)?.value
            brakeTrigger shouldBe "DATA_RECEIVED"
        }

        test("composition: export is deterministic — two exports of same model are byte-identical") {
            val model = buildClassicComposition()
            val xml1 = exporter.export(model)
            val xml2 = exporter.export(model)
            xml1 shouldBe xml2
        }

        test("composition: warnings empty for well-formed model") {
            val model = buildClassicComposition()
            val xml = exporter.export(model)
            val result = importer.importFromString(xml)
            // Only acceptable warning: xsi:schemaLocation absent (we check for it by content)
            val unexpectedWarnings =
                result.warnings.filter { !it.contains("schemaLocation") && !it.contains("namespace") }
            unexpectedWarnings.shouldBeEmpty()
        }

        test("composition: exported ARXML does not contain raw XML entities") {
            val model = buildClassicComposition()
            val xml = exporter.export(model)
            xml shouldNotContain "&apos;"
            xml shouldNotContain "&amp;lt;"
        }
    })
