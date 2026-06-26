package dev.kuml.codegen.m2m.arxml

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.kerml.KermlFeature
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Tests for [Sysml2ToArxmlTransformer].
 *
 * V3.1.35 — initial implementation.
 */
class Sysml2ToArxmlTransformerTest :
    FunSpec({

        val transformer = Sysml2ToArxmlTransformer()
        val ctx = TransformContext()

        fun makePortDef(
            name: String,
            direction: String,
        ): PartDefinition =
            PartDefinition(
                id = UUID.randomUUID().toString(),
                name = name,
                metadata =
                    mapOf(
                        "portDefinition" to KumlMetaValue.Text("true"),
                        "direction" to KumlMetaValue.Text(direction),
                    ),
            )

        fun makeBlock(
            name: String,
            vararg portFeatures: KermlFeature,
        ): PartDefinition =
            PartDefinition(
                id = UUID.randomUUID().toString(),
                name = name,
                features = portFeatures.toList(),
                metadata = mapOf("stereotype" to KumlMetaValue.Text("SoftwareComponent")),
            )

        fun makeInterfaceBlock(name: String): PartDefinition =
            PartDefinition(
                id = UUID.randomUUID().toString(),
                name = name,
                metadata =
                    mapOf(
                        "interfaceBlock" to KumlMetaValue.Text("true"),
                        "stereotype" to KumlMetaValue.Text("interface"),
                    ),
            )

        fun portFeature(
            name: String,
            portDefId: String,
            direction: String,
        ): KermlFeature =
            KermlFeature(
                id = UUID.randomUUID().toString(),
                name = name,
                typeId = portDefId,
                definitionId = portDefId,
                metadata = mapOf("direction" to KumlMetaValue.Text(direction)),
            )

        test("id is sysml2-to-arxml") {
            transformer.id shouldBe "sysml2-to-arxml"
        }

        test("Block maps back to UmlComponent with SoftwareComponent stereotype") {
            val block = makeBlock("BrakeController")
            val sysml2 = Sysml2Model(name = "Test", definitions = listOf(block))
            val result = transformer.transform(sysml2, ctx) as TransformResult.Success<*>
            val kuml = result.output as dev.kuml.core.model.KumlModel
            val rootPkg = kuml.root as UmlPackage
            val comp: UmlComponent? = rootPkg.members.filterIsInstance<UmlComponent>().firstOrNull { it.name == "BrakeController" }
            comp.shouldNotBeNull()
            ("SoftwareComponent" in (comp as UmlComponent).stereotypes) shouldBe true
        }

        test("PortUsage maps back to UmlPort with AutosarPort stereotype and direction") {
            val portDef = makePortDef("BrakePortType", "provided")
            val feature = portFeature("BrakePort", portDef.id, "provided")
            val block = makeBlock("BrakeController", feature)
            val sysml2 = Sysml2Model(name = "Test", definitions = listOf(portDef, block))
            val result = transformer.transform(sysml2, ctx) as TransformResult.Success<*>
            val kuml = result.output as dev.kuml.core.model.KumlModel
            val rootPkg = kuml.root as UmlPackage
            val comp: UmlComponent = rootPkg.members.filterIsInstance<UmlComponent>().first { it.name == "BrakeController" }
            val port: UmlPort? = comp.ports.firstOrNull { it.name == "BrakePort" }
            port.shouldNotBeNull()
            ("AutosarPort" in (port as UmlPort).stereotypes) shouldBe true
            ((port as UmlPort).metadata["direction"] as? KumlMetaValue.Text)?.value shouldBe "provided"
        }

        test("interfaceBlock PartDefinition maps back to UmlInterface with ComInterface stereotype") {
            val ifaceBlock = makeInterfaceBlock("IBrake")
            val sysml2 = Sysml2Model(name = "Test", definitions = listOf(ifaceBlock))
            val result = transformer.transform(sysml2, ctx) as TransformResult.Success<*>
            val kuml = result.output as dev.kuml.core.model.KumlModel
            val rootPkg = kuml.root as UmlPackage
            val iface: UmlInterface? = rootPkg.members.filterIsInstance<UmlInterface>().firstOrNull { it.name == "IBrake" }
            iface.shouldNotBeNull()
            ("ComInterface" in (iface as UmlInterface).stereotypes) shouldBe true
        }

        test("result KumlModel root is a UmlPackage named AUTOSAR, language=UML") {
            val block = makeBlock("SomeComp")
            val sysml2 = Sysml2Model(name = "Test", definitions = listOf(block))
            val result = transformer.transform(sysml2, ctx) as TransformResult.Success<*>
            val kuml = result.output as dev.kuml.core.model.KumlModel
            kuml.language shouldBe ModelingLanguage.UML
            kuml.level shouldBe ModelLevel.PIM
            val rootPkg = kuml.root as? UmlPackage
            rootPkg.shouldNotBeNull()
            rootPkg.name shouldBe "AUTOSAR"
        }

        test("TransformResult.Success carries non-empty TransformTrace") {
            val block = makeBlock("Comp1")
            val sysml2 = Sysml2Model(name = "Test", definitions = listOf(block))
            val result = transformer.transform(sysml2, ctx) as TransformResult.Success<*>
            result.trace.links.shouldNotBeEmpty()
        }

        test("trace contains block-to-swc link") {
            val block = makeBlock("Comp1")
            val sysml2 = Sysml2Model(name = "Test", definitions = listOf(block))
            val result = transformer.transform(sysml2, ctx) as TransformResult.Success<*>
            result.trace.links
                .any { it.ruleId == "block-to-swc" }
                .shouldBeTrue()
        }
    })
