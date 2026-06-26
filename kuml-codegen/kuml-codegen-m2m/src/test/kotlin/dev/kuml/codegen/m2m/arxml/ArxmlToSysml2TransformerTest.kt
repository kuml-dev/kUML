package dev.kuml.codegen.m2m.arxml

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

/**
 * Tests for [ArxmlToSysml2Transformer].
 *
 * V3.1.35 — initial implementation.
 */
class ArxmlToSysml2TransformerTest :
    FunSpec({

        val transformer = ArxmlToSysml2Transformer()
        val ctx = TransformContext()

        fun kumlModel(vararg members: dev.kuml.uml.UmlNamedElement): KumlModel {
            val rootPkg =
                UmlPackage(
                    id = UUID.randomUUID().toString(),
                    name = "AUTOSAR",
                    members = members.toList(),
                )
            return KumlModel(
                root = rootPkg,
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "TestModel",
            )
        }

        fun makeComponent(
            name: String,
            vararg ports: UmlPort,
        ): UmlComponent =
            UmlComponent(
                id = UUID.randomUUID().toString(),
                name = name,
                ports = ports.toList(),
                stereotypes = listOf("SoftwareComponent"),
                metadata = mapOf("kind" to KumlMetaValue.Text("application")),
            )

        fun makePort(
            name: String,
            direction: String,
        ): UmlPort =
            UmlPort(
                id = UUID.randomUUID().toString(),
                name = name,
                stereotypes = listOf("AutosarPort"),
                metadata = mapOf("direction" to KumlMetaValue.Text(direction)),
            )

        fun makeInterface(name: String): UmlInterface =
            UmlInterface(
                id = UUID.randomUUID().toString(),
                name = name,
                stereotypes = listOf("ComInterface"),
            )

        test("id is arxml-to-sysml2") {
            transformer.id shouldBe "arxml-to-sysml2"
        }

        test("SoftwareComponent maps to a PartDefinition") {
            val comp = makeComponent("BrakeController")
            val model = kumlModel(comp)
            val result = transformer.transform(model, ctx)
            result.shouldBeInstanceOf<TransformResult.Success<*>>()
            val success = result as TransformResult.Success<*>
            val sysml2 = success.output as dev.kuml.sysml2.Sysml2Model
            val blocks =
                sysml2.definitions
                    .filterIsInstance<PartDefinition>()
                    .filter { (it.metadata["portDefinition"] as? KumlMetaValue.Text)?.value != "true" }
            blocks.any { it.name == "BrakeController" }.shouldBeTrue()
        }

        test("AutosarPort maps to a PortDefinition with preserved direction") {
            val portProvided = makePort("BrakePort", "provided")
            val portRequired = makePort("SensorPort", "required")
            val comp = makeComponent("BrakeController", portProvided, portRequired)
            val model = kumlModel(comp)
            val result = transformer.transform(model, ctx) as TransformResult.Success<*>
            val sysml2 = result.output as dev.kuml.sysml2.Sysml2Model
            // PartDefinitions with portDefinition=true represent port types
            val portDefs =
                sysml2.definitions
                    .filterIsInstance<PartDefinition>()
                    .filter { (it.metadata["portDefinition"] as? KumlMetaValue.Text)?.value == "true" }
            portDefs
                .any {
                    (it.metadata["direction"] as? KumlMetaValue.Text)?.value == "provided"
                }.shouldBeTrue()
            portDefs
                .any {
                    (it.metadata["direction"] as? KumlMetaValue.Text)?.value == "required"
                }.shouldBeTrue()
        }

        test("ComInterface maps to an interfaceBlock-flagged PartDefinition") {
            val iface = makeInterface("IBrake")
            val model = kumlModel(iface)
            val result = transformer.transform(model, ctx) as TransformResult.Success<*>
            val sysml2 = result.output as dev.kuml.sysml2.Sysml2Model
            val interfaceBlock =
                sysml2.definitions
                    .filterIsInstance<PartDefinition>()
                    .firstOrNull { it.name == "IBrake" }
            interfaceBlock.shouldNotBeNull()
            (interfaceBlock.metadata["interfaceBlock"] as? KumlMetaValue.Text)?.value shouldBe "true"
        }

        test("result Sysml2Model carries a BdDiagram with all element ids") {
            val comp = makeComponent("Comp1")
            val iface = makeInterface("IFace1")
            val model = kumlModel(comp, iface)
            val result = transformer.transform(model, ctx) as TransformResult.Success<*>
            val sysml2 = result.output as dev.kuml.sysml2.Sysml2Model
            val diagram = sysml2.diagrams.filterIsInstance<BdDiagram>().firstOrNull()
            diagram.shouldNotBeNull()
            diagram.elementIds.shouldNotBeEmpty()
        }

        test("TransformResult.Success carries a non-empty TransformTrace") {
            val comp = makeComponent("Comp1")
            val model = kumlModel(comp)
            val result = transformer.transform(model, ctx) as TransformResult.Success<*>
            result.trace.links.shouldNotBeEmpty()
        }

        test("trace contains swc-to-block link for SoftwareComponent") {
            val comp = makeComponent("MyComp")
            val model = kumlModel(comp)
            val result = transformer.transform(model, ctx) as TransformResult.Success<*>
            result.trace.links
                .any { it.ruleId == "swc-to-block" }
                .shouldBeTrue()
        }
    })
