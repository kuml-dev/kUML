package dev.kuml.codegen.m2m.arxml

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Round-trip tests: KumlModel → [ArxmlToSysml2Transformer] → [Sysml2ToArxmlTransformer] → KumlModel.
 *
 * Equality is checked by NAME, STEREOTYPE and METADATA — NOT by UUID (UUIDs are re-assigned
 * by each transformer, same convention as [dev.kuml.io.arxml.ArxmlClassicRoundtripTest]).
 *
 * V3.1.35 — initial implementation.
 */
class ArxmlSysml2RoundtripTest :
    FunSpec({

        val toSysml2 = ArxmlToSysml2Transformer()
        val toArxml = Sysml2ToArxmlTransformer()
        val ctx = TransformContext()

        fun makeModel(vararg members: dev.kuml.uml.UmlNamedElement): KumlModel {
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

        test("Classic KumlModel -> ArxmlToSysml2 -> Sysml2ToArxml -> structurally-equal KumlModel (component names preserved)") {
            val portProvided =
                UmlPort(
                    id = UUID.randomUUID().toString(),
                    name = "BrakeOutput",
                    stereotypes = listOf("AutosarPort"),
                    metadata = mapOf("direction" to KumlMetaValue.Text("provided")),
                )
            val portRequired =
                UmlPort(
                    id = UUID.randomUUID().toString(),
                    name = "SensorInput",
                    stereotypes = listOf("AutosarPort"),
                    metadata = mapOf("direction" to KumlMetaValue.Text("required")),
                )
            val comp =
                UmlComponent(
                    id = UUID.randomUUID().toString(),
                    name = "BrakeController",
                    ports = listOf(portProvided, portRequired),
                    stereotypes = listOf("SoftwareComponent"),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                )
            val iface =
                UmlInterface(
                    id = UUID.randomUUID().toString(),
                    name = "IBrakeInterface",
                    stereotypes = listOf("ComInterface"),
                )

            val originalModel = makeModel(comp, iface)

            // Forward: KumlModel → SysML 2
            val sysml2Result = toSysml2.transform(originalModel, ctx) as TransformResult.Success<*>
            val sysml2Model = sysml2Result.output as dev.kuml.sysml2.Sysml2Model

            // Reverse: SysML 2 → KumlModel
            val reverseResult = toArxml.transform(sysml2Model, ctx) as TransformResult.Success<*>
            val reverseModel = reverseResult.output as KumlModel

            // Verify: component names + stereotypes preserved
            val reverseRoot = reverseModel.root as UmlPackage
            val reverseComp: UmlComponent? =
                reverseRoot.members.filterIsInstance<UmlComponent>().firstOrNull {
                    it.name == "BrakeController"
                }
            reverseComp.shouldNotBeNull()
            ("SoftwareComponent" in (reverseComp as UmlComponent).stereotypes) shouldBe true

            // Verify: interface preserved
            val reverseIface: UmlInterface? =
                reverseRoot.members.filterIsInstance<UmlInterface>().firstOrNull {
                    it.name ==
                        "IBrakeInterface"
                }
            reverseIface.shouldNotBeNull()
            ("ComInterface" in (reverseIface as UmlInterface).stereotypes) shouldBe true
        }

        test("port direction is preserved through full round-trip") {
            val portProvided =
                UmlPort(
                    id = UUID.randomUUID().toString(),
                    name = "ProvidedPort",
                    stereotypes = listOf("AutosarPort"),
                    metadata = mapOf("direction" to KumlMetaValue.Text("provided")),
                )
            val portRequired =
                UmlPort(
                    id = UUID.randomUUID().toString(),
                    name = "RequiredPort",
                    stereotypes = listOf("AutosarPort"),
                    metadata = mapOf("direction" to KumlMetaValue.Text("required")),
                )
            val comp =
                UmlComponent(
                    id = UUID.randomUUID().toString(),
                    name = "SomeComp",
                    ports = listOf(portProvided, portRequired),
                    stereotypes = listOf("SoftwareComponent"),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                )
            val model = makeModel(comp)

            val sysml2 = (toSysml2.transform(model, ctx) as TransformResult.Success<*>).output as dev.kuml.sysml2.Sysml2Model
            val reverse = (toArxml.transform(sysml2, ctx) as TransformResult.Success<*>).output as KumlModel
            val reverseRoot = reverse.root as UmlPackage
            val reverseComp: UmlComponent = reverseRoot.members.filterIsInstance<UmlComponent>().first { it.name == "SomeComp" }

            (reverseComp as UmlComponent)
                .ports
                .any {
                    it.name == "ProvidedPort" && (it.metadata["direction"] as? KumlMetaValue.Text)?.value == "provided"
                }.shouldBeTrue()
            (reverseComp as UmlComponent)
                .ports
                .any {
                    it.name == "RequiredPort" && (it.metadata["direction"] as? KumlMetaValue.Text)?.value == "required"
                }.shouldBeTrue()
        }

        test("round-trip root package is named AUTOSAR with language=UML") {
            val comp =
                UmlComponent(
                    id = UUID.randomUUID().toString(),
                    name = "Comp1",
                    stereotypes = listOf("SoftwareComponent"),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                )
            val model = makeModel(comp)
            val sysml2 = (toSysml2.transform(model, ctx) as TransformResult.Success<*>).output as dev.kuml.sysml2.Sysml2Model
            val reverse = (toArxml.transform(sysml2, ctx) as TransformResult.Success<*>).output as KumlModel
            reverse.language shouldBe ModelingLanguage.UML
            (reverse.root as UmlPackage).name shouldBe "AUTOSAR"
        }

        test("multiple components and interfaces survive round-trip with correct counts") {
            val members =
                (1..3).map { i ->
                    UmlComponent(
                        id = UUID.randomUUID().toString(),
                        name = "Comp$i",
                        stereotypes = listOf("SoftwareComponent"),
                        metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                    )
                } +
                    (1..2).map { i ->
                        UmlInterface(
                            id = UUID.randomUUID().toString(),
                            name = "IFace$i",
                            stereotypes = listOf("ComInterface"),
                        )
                    }
            val namedMembers: List<dev.kuml.uml.UmlNamedElement> = members.map { it as dev.kuml.uml.UmlNamedElement }
            val model = makeModel(*namedMembers.toTypedArray())
            val sysml2 = (toSysml2.transform(model, ctx) as TransformResult.Success<*>).output as dev.kuml.sysml2.Sysml2Model
            val reverse = (toArxml.transform(sysml2, ctx) as TransformResult.Success<*>).output as KumlModel
            val reverseRoot = reverse.root as UmlPackage
            reverseRoot.members.filterIsInstance<UmlComponent>().size shouldBe 3
            reverseRoot.members.filterIsInstance<UmlInterface>().size shouldBe 2
        }
    })
