package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private fun buildTestModel(version: ArxmlVersion): UmlPackage {
    val swc =
        UmlComponent(
            id = "comp-1",
            name = "BrakeController",
            stereotypes = listOf("SoftwareComponent"),
            metadata = mapOf("kind" to KumlMetaValue.Text("composition")),
            ports =
                listOf(
                    UmlPort(
                        id = "port-1",
                        name = "BrakeOut",
                        stereotypes = listOf("AutosarPort"),
                        metadata = mapOf("direction" to KumlMetaValue.Text("provided")),
                    ),
                    UmlPort(
                        id = "port-2",
                        name = "SpeedIn",
                        stereotypes = listOf("AutosarPort"),
                        metadata = mapOf("direction" to KumlMetaValue.Text("required")),
                    ),
                ),
        )
    val iface =
        UmlInterface(
            id = "iface-1",
            name = "IBrakeData",
            stereotypes = listOf("ComInterface"),
        )
    val innerPkg =
        UmlPackage(
            id = "pkg-1",
            name = "BrakeSystem",
            members = listOf(swc, iface),
        )
    return UmlPackage(
        id = "root-1",
        name = "AUTOSAR",
        members = listOf(innerPkg),
    )
}

class ArxmlRoundtripTest :
    FunSpec({

        test("write then read returns structurally equivalent UmlPackage") {
            val original = buildTestModel(ArxmlVersion.R22_11)
            val writer = ArxmlWriter(version = ArxmlVersion.R22_11)
            val reader = ArxmlReader()

            val xml = writer.write(original)
            val result = reader.readFromString(xml)

            result.rootPackage.members shouldHaveSize 1
            val pkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            pkg.name shouldBe "BrakeSystem"

            val components = pkg.members.filterIsInstance<UmlComponent>()
            components shouldHaveSize 1
            val comp = components[0]
            comp.name shouldBe "BrakeController"
            comp.stereotypes shouldBe listOf("SoftwareComponent")
            comp.ports shouldHaveSize 2

            val providedPort = comp.ports.first { it.name == "BrakeOut" }
            (providedPort.metadata["direction"] as? KumlMetaValue.Text)?.value shouldBe "provided"

            val requiredPort = comp.ports.first { it.name == "SpeedIn" }
            (requiredPort.metadata["direction"] as? KumlMetaValue.Text)?.value shouldBe "required"

            val interfaces = pkg.members.filterIsInstance<UmlInterface>()
            interfaces shouldHaveSize 1
            interfaces[0].name shouldBe "IBrakeData"
            interfaces[0].stereotypes shouldBe listOf("ComInterface")
        }

        test("roundtrip preserves nested package hierarchy") {
            val child =
                UmlPackage(
                    id = "child-1",
                    name = "ChildPkg",
                    members = emptyList(),
                )
            val parent =
                UmlPackage(
                    id = "parent-1",
                    name = "ParentPkg",
                    members = listOf(child),
                )
            val root =
                UmlPackage(
                    id = "root-1",
                    name = "AUTOSAR",
                    members = listOf(parent),
                )

            val writer = ArxmlWriter(version = ArxmlVersion.R22_11)
            val reader = ArxmlReader()
            val xml = writer.write(root)
            val result = reader.readFromString(xml)

            val parentPkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            parentPkg.name shouldBe "ParentPkg"
            val childPkg = parentPkg.members[0].shouldBeInstanceOf<UmlPackage>()
            childPkg.name shouldBe "ChildPkg"
        }

        test("roundtrip preserves runnables via INTERNAL-BEHAVIORS") {
            val swcWithRunnables =
                UmlComponent(
                    id = "comp-r",
                    name = "EngineController",
                    stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
                    metadata = mapOf("kind" to KumlMetaValue.Text("application")),
                    operations =
                        listOf(
                            UmlOperation(
                                id = "op-1",
                                name = "Init",
                                stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
                            ),
                            UmlOperation(
                                id = "op-2",
                                name = "Cyclic10ms",
                                stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
                            ),
                        ),
                )
            val root =
                UmlPackage(
                    id = "root-r",
                    name = "AUTOSAR",
                    members =
                        listOf(
                            UmlPackage(
                                id = "pkg-r",
                                name = "Engine",
                                members = listOf(swcWithRunnables),
                            ),
                        ),
                )

            val writer = ArxmlWriter(version = ArxmlVersion.R22_11)
            val reader = ArxmlReader()

            val xml = writer.write(root)

            // Structural check: XML must contain runnable element names
            xml shouldContain ArxmlSchema.ELEM_INTERNAL_BEHAVIORS
            xml shouldContain ArxmlSchema.ELEM_RUNNABLE_ENTITY
            xml shouldContain "Init"
            xml shouldContain "Cyclic10ms"

            // Roundtrip check: reader must reconstruct runnables as operations
            val result = reader.readFromString(xml)
            val pkg = result.rootPackage.members[0].shouldBeInstanceOf<UmlPackage>()
            val comp = pkg.members.filterIsInstance<UmlComponent>()[0]
            comp.name shouldBe "EngineController"
            comp.operations shouldHaveSize 2
            val names = comp.operations.map { it.name }.toSet()
            names shouldBe setOf("Init", "Cyclic10ms")
            comp.operations.forEach { op ->
                op.stereotypes shouldBe listOf(ArxmlSchema.STEREOTYPE_RUNNABLE)
            }
        }

        test("roundtrip preserves AUTOSAR version") {
            val model = buildTestModel(ArxmlVersion.R23_11)
            val writer = ArxmlWriter(version = ArxmlVersion.R23_11)
            val reader = ArxmlReader()

            val xml = writer.write(model)
            val result = reader.readFromString(xml)

            result.version shouldBe ArxmlVersion.R23_11
        }
    })
