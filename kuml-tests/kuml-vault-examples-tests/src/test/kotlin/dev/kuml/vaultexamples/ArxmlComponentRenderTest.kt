package dev.kuml.vaultexamples

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.io.arxml.ArxmlClassicExporter
import dev.kuml.io.arxml.ArxmlClassicImporter
import dev.kuml.io.arxml.ArxmlSchema
import dev.kuml.io.arxml.ArxmlVersion
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.UmlContentSizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain

/**
 * Render-as-SVG leg of the ARXML composition roundtrip.
 *
 * Imports an ARXML composition (3 SWCs, 2 ports each, 2 interfaces, 4 runnables),
 * builds a component diagram from the imported model, renders it as SVG, and
 * verifies structural completeness.
 *
 * Placed in kuml-vault-examples-tests (which already depends on kuml-io-svg +
 * kuml-layout-elk + kuml-io-arxml) to avoid polluting kuml-io-svg's dependency graph
 * with the JVM-only JDOM2-based kuml-io-arxml module.
 *
 * V3.1.36 — initial implementation.
 */
class ArxmlComponentRenderTest :
    FunSpec({
        val exporter = ArxmlClassicExporter(version = ArxmlVersion.R22_11)
        val importer = ArxmlClassicImporter()

        /**
         * Build a minimal ARXML composition for render testing.
         * 3 SWCs: SpeedSensorSwc, BrakeControllerSwc, DiagSwc.
         * 2 interfaces: ISpeed (SR), IDiag (CS).
         */
        fun buildCompositionModel(): KumlModel {
            val iSpeed =
                UmlInterface(
                    id = "iface-speed",
                    name = "ISpeed",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
                )
            val iDiag =
                UmlInterface(
                    id = "iface-diag",
                    name = "IDiag",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
                    metadata = mapOf("isService" to KumlMetaValue.Text("true")),
                )
            val ifacesPkg =
                UmlPackage(id = "pkg-ifaces", name = "Interfaces", members = listOf(iSpeed, iDiag))

            fun makeSwc(
                id: String,
                name: String,
                pPortName: String,
                rPortName: String,
                pIfaceRef: String,
                rIfaceRef: String,
                runnableName: String,
            ): UmlComponent =
                UmlComponent(
                    id = id,
                    name = name,
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                    ports =
                        listOf(
                            UmlPort(
                                id = "$id-pp",
                                name = pPortName,
                                stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
                                metadata =
                                    mapOf(
                                        "direction" to KumlMetaValue.Text("provided"),
                                        "interfaceRef" to KumlMetaValue.Text(pIfaceRef),
                                    ),
                            ),
                            UmlPort(
                                id = "$id-rp",
                                name = rPortName,
                                stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
                                metadata =
                                    mapOf(
                                        "direction" to KumlMetaValue.Text("required"),
                                        "interfaceRef" to KumlMetaValue.Text(rIfaceRef),
                                    ),
                            ),
                        ),
                    operations =
                        listOf(
                            UmlOperation(
                                id = "$id-op",
                                name = runnableName,
                                stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
                                metadata = mapOf("trigger" to KumlMetaValue.Text("TIMING")),
                            ),
                        ),
                )

            val speedSensor =
                makeSwc(
                    "comp-speed",
                    "SpeedSensorSwc",
                    "SpeedOut",
                    "DiagIn",
                    "/Interfaces/ISpeed",
                    "/Interfaces/IDiag",
                    "ReadSensor",
                )
            val brakeCtrl =
                makeSwc(
                    "comp-brake",
                    "BrakeControllerSwc",
                    "DiagOut",
                    "SpeedIn",
                    "/Interfaces/IDiag",
                    "/Interfaces/ISpeed",
                    "CalculateBrake",
                )
            val diagSwc =
                makeSwc(
                    "comp-diag",
                    "DiagSwc",
                    "DiagOut2",
                    "DiagIn2",
                    "/Interfaces/IDiag",
                    "/Interfaces/IDiag",
                    "ProcessDiag",
                )

            val compsPkg =
                UmlPackage(
                    id = "pkg-comps",
                    name = "Components",
                    members = listOf(speedSensor, brakeCtrl, diagSwc),
                )
            val root =
                UmlPackage(id = "root", name = "AUTOSAR", members = listOf(ifacesPkg, compsPkg))
            return KumlModel(
                root = root,
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "ArxmlRenderTest",
            )
        }

        test("ARXML composition: imported model renders as non-empty SVG") {
            val model = buildCompositionModel()

            // Export to ARXML then re-import (full roundtrip)
            val xml = exporter.export(model)
            val importResult = importer.importFromString(xml)
            val importedModel = importResult.model
            val importedRoot = importedModel.root as UmlPackage

            // Collect all components from nested packages for the component diagram
            val allComponents = mutableListOf<UmlComponent>()
            val allInterfaces = mutableListOf<UmlInterface>()
            for (pkg in importedRoot.members.filterIsInstance<UmlPackage>()) {
                allComponents.addAll(pkg.members.filterIsInstance<UmlComponent>())
                allInterfaces.addAll(pkg.members.filterIsInstance<UmlInterface>())
            }

            // Build a flat KumlDiagram from the imported elements for rendering
            // (component diagram with all components + interfaces at the top level)
            val diagram =
                dev.kuml.core.model.KumlDiagram(
                    name = "AUTOSAR Composition",
                    type = dev.kuml.core.model.DiagramType.COMPONENT,
                    elements = allComponents + allInterfaces,
                )

            val elkEngine = ElkLayoutEngineProvider().engine()
            val sizeProvider = UmlContentSizeProvider(diagram)
            val graph = UmlLayoutBridge.toLayoutGraph(diagram, sizeProvider)
            val layout = elkEngine.layout(graph, LayoutHints.DEFAULT)
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg.shouldNotBeEmpty()
            svg shouldContain "<svg"
            // Each SWC name should appear as SVG text
            svg shouldContain "SpeedSensorSwc"
            svg shouldContain "BrakeControllerSwc"
            svg shouldContain "DiagSwc"
            // No raw XML entities in the output
            svg shouldNotContain "&apos;"
            svg shouldNotContain "&amp;lt;"
        }

        test("ARXML composition: SVG contains all interface names") {
            val model = buildCompositionModel()
            val xml = exporter.export(model)
            val importResult = importer.importFromString(xml)
            val importedRoot = importResult.model.root as UmlPackage

            val allInterfaces = mutableListOf<UmlInterface>()
            for (pkg in importedRoot.members.filterIsInstance<UmlPackage>()) {
                allInterfaces.addAll(pkg.members.filterIsInstance<UmlInterface>())
            }

            val allComponents = mutableListOf<UmlComponent>()
            for (pkg in importedRoot.members.filterIsInstance<UmlPackage>()) {
                allComponents.addAll(pkg.members.filterIsInstance<UmlComponent>())
            }

            val diagram =
                dev.kuml.core.model.KumlDiagram(
                    name = "AUTOSAR Interfaces",
                    type = dev.kuml.core.model.DiagramType.COMPONENT,
                    elements = allComponents + allInterfaces,
                )

            val elkEngine = ElkLayoutEngineProvider().engine()
            val sizeProvider = UmlContentSizeProvider(diagram)
            val graph = UmlLayoutBridge.toLayoutGraph(diagram, sizeProvider)
            val layout = elkEngine.layout(graph, LayoutHints.DEFAULT)
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())

            svg shouldContain "ISpeed"
            svg shouldContain "IDiag"
        }
    })
