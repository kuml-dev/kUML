package dev.kuml.sysml2.projection

import dev.kuml.sysml2.AllocationUsage
import dev.kuml.sysml2.FlowUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.effectiveDirection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

private fun vehicleModel(): Sysml2Model =
    sysml2Model(name = "V") {
        attributeDef(name = "Fuel", unitSymbol = "l")
        portDef(name = "FuelPort") {
            flowProperty(name = "fuel", typeId = "Fuel", direction = PortDirection.In)
            flowProperty(name = "exhaust", typeId = "Fuel", direction = PortDirection.Out)
        }
        partDef(name = "Crank") {
            port(name = "out", typeId = "FuelPort", direction = PortDirection.Out)
        }
        partDef(name = "Engine") {
            port(name = "injector", typeId = "FuelPort", direction = PortDirection.In, conjugated = true)
            part(name = "crank", typeId = "Crank")
        }
        partDef(name = "Tank") {
            port(name = "outlet", typeId = "FuelPort", direction = PortDirection.Out)
        }
        partDef(name = "Vehicle") {
            port(name = "fuelIn", typeId = "FuelPort", direction = PortDirection.In)
            part(name = "engine", typeId = "Engine")
            refPart(name = "tank", typeId = "Tank")
            connect(name = "pipe", typeId = "FuelPort", sourceEndId = "tank.outlet", targetEndId = "engine.injector")
            connect(name = "deleg", typeId = "FuelPort", sourceEndId = "Vehicle::fuelIn", targetEndId = "engine.injector")
            flow(item = "Fuel", from = "tank.outlet", to = "engine.injector", via = "pipe")
        }
    }

class PortsFlowsProjectionTest :
    FunSpec(body = {
        test("effectiveDirection swaps for conjugated ports and keeps inout") {
            val m = vehicleModel()
            val plain = m.usages.filterIsInstance<PortUsage>().first { it.id == "Tank::outlet" }
            val conj = m.usages.filterIsInstance<PortUsage>().first { it.id == "Engine::injector" }
            plain.effectiveDirection(model = m, flowPropertyId = "FuelPort::fuel") shouldBe PortDirection.In
            conj.effectiveDirection(model = m, flowPropertyId = "FuelPort::fuel") shouldBe PortDirection.Out
            conj.effectiveDirection(model = m, flowPropertyId = "FuelPort::exhaust") shouldBe PortDirection.In
            plain.effectiveDirection(model = m, flowPropertyId = "FuelPort::nope").shouldBeNull()
        }

        test("dotted paths are qualified, qualified ids pass through, limits enforced") {
            Sysml2PathResolver.toQualified(ownerId = "O", dotted = "a.b.c") shouldBe "O::a::b::c"
            Sysml2PathResolver.toQualified(ownerId = "O", dotted = "O::a::b") shouldBe "O::a::b"
            Sysml2PathResolver.toQualified(ownerId = "O", dotted = "plain") shouldBe "plain"
            shouldThrow<IllegalArgumentException> { Sysml2PathResolver.toQualified(ownerId = "O", dotted = "a..b") }
            shouldThrow<IllegalArgumentException> {
                Sysml2PathResolver.toQualified(
                    ownerId = "O",
                    dotted = (1..9).joinToString(".") { "s$it" },
                )
            }
            shouldThrow<IllegalArgumentException> { Sysml2PathResolver.toQualified(ownerId = "O", dotted = "a." + "x".repeat(600)) }
        }

        test("resolver: part port, owner port, deeper path, unresolvable") {
            val m = vehicleModel()
            val nodes = setOf("Vehicle::engine", "Vehicle::tank")
            val r1 =
                Sysml2PathResolver
                    .resolve(
                        model = m,
                        ownerId = "Vehicle",
                        endpointId = "Vehicle::tank::outlet",
                        visibleNodeIds = nodes,
                    ).shouldNotBeNull()
            r1.nodeId shouldBe "Vehicle::tank"
            r1.terminalPort.shouldNotBeNull().id shouldBe "Tank::outlet"
            val r2 =
                Sysml2PathResolver
                    .resolve(
                        model = m,
                        ownerId = "Vehicle",
                        endpointId = "Vehicle::fuelIn",
                        visibleNodeIds = nodes,
                    ).shouldNotBeNull()
            r2.nodeId.shouldBeNull()
            r2.ownerPort.shouldNotBeNull().id shouldBe "Vehicle::fuelIn"
            val r3 =
                Sysml2PathResolver
                    .resolve(
                        model = m,
                        ownerId = "Vehicle",
                        endpointId = "Vehicle::engine::crank::out",
                        visibleNodeIds = nodes,
                    ).shouldNotBeNull()
            r3.steps shouldBe listOf("crank", "out")
            r3.terminalPort.shouldNotBeNull().id shouldBe "Crank::out"
            Sysml2PathResolver.resolve(model = m, ownerId = "Vehicle", endpointId = "Elsewhere::x", visibleNodeIds = nodes).shouldBeNull()
            Sysml2PathResolver.resolve(model = m, ownerId = "Vehicle", endpointId = "Vehicle::ghost", visibleNodeIds = nodes).shouldBeNull()
        }

        test("ibd projection: frame only for owner port or deep path, flows carried by connector") {
            val m = vehicleModel()
            val p = sysml2IbdProjection(model = m, diagram = IbdDiagram(name = "i", ownerId = "Vehicle"))
            p.showFrame shouldBe true
            p.boundaryPorts.map { it.id } shouldBe listOf("Vehicle::fuelIn")
            p.connections shouldHaveSize 2
            p.carriedFlows shouldHaveSize 1
            p.ownLineFlows.shouldBeEmpty()

            val legacy =
                sysml2Model(name = "L") {
                    portDef(name = "P")
                    partDef(name = "A") { port(name = "o", typeId = "P") }
                    partDef(name = "B") { port(name = "i", typeId = "P") }
                    partDef(name = "S") {
                        part(name = "a", typeId = "A")
                        part(name = "b", typeId = "B")
                        connect(name = "c", typeId = "P", sourceEndId = "S::a::o", targetEndId = "S::b::i")
                    }
                }
            sysml2IbdProjection(model = legacy, diagram = IbdDiagram(name = "i", ownerId = "S")).showFrame shouldBe false
        }

        test("bdd projection: composition, aggregation, hidden features") {
            val m = vehicleModel()
            val d = m.definitions.map { it.id }
            val proj = sysml2BddProjection(model = m, diagram = dev.kuml.sysml2.BdDiagram(name = "b", elementIds = d))
            proj.edges.map { it.id } shouldContain "comp:Vehicle::engine"
            proj.edges.map { it.id } shouldContain "agg:Vehicle::tank"
            proj.hiddenFeatureIds shouldContain "Vehicle::engine"
            proj.signalsByDef["Vehicle"] shouldBe listOf("Fuel")
            proj.signalsByDef["Tank"] shouldBe listOf("Fuel")
            // BDD showing only Vehicle keeps all lines (targets invisible)
            val only = sysml2BddProjection(model = m, diagram = dev.kuml.sysml2.BdDiagram(name = "b", elementIds = listOf("Vehicle")))
            only.edges.shouldBeEmpty()
            only.hiddenFeatureIds shouldBe setOf("Vehicle::flow1")
        }

        test("allocate validates targets and prints back") {
            shouldThrow<IllegalArgumentException> { sysml2Model(name = "X") { allocate(from = "A", to = "B") } }
            val m =
                sysml2Model(name = "X") {
                    val a = partDef(name = "A")
                    val b = partDef(name = "B")
                    allocate(from = a, to = b, name = "al")
                }
            m.usages.filterIsInstance<AllocationUsage>() shouldHaveSize 1
            val dsl = Sysml2DslPrinter.print(m)
            dsl shouldContain "allocate(from = \"A\", to = \"B\", name = \"al\", id = \"alloc:al\")"
        }

        test("printer emits new fields only when they differ from the default") {
            val dsl = Sysml2DslPrinter.print(vehicleModel())
            dsl shouldContain "direction = PortDirection.Out"
            dsl shouldContain "conjugated = true"
            dsl shouldContain "refPart(name = \"tank\""
            dsl shouldContain "flowProperty(name = \"fuel\""
            dsl shouldContain "flow(item = \"Fuel\""
            dsl shouldContain "unitSymbol = \"l\""
            val legacy =
                sysml2Model(name = "L") {
                    portDef(name = "P")
                    partDef(name = "A") { port(name = "o", typeId = "P") }
                }
            val ld = Sysml2DslPrinter.print(legacy)
            ld shouldNotContain "direction"
            ld shouldNotContain "conjugated"
            ld shouldNotContain "unitSymbol"
        }

        test("JSON roundtrip of all new types and legacy JSON still decodes with defaults") {
            val json = Json { classDiscriminator = "type" }
            val m = vehicleModel()
            val enc = json.encodeToString(Sysml2Model.serializer(), m)
            json.decodeFromString(Sysml2Model.serializer(), enc) shouldBe m
            m.usages.filterIsInstance<FlowUsage>() shouldHaveSize 1
            // legacy shape: a port usage without direction / conjugation
            val legacyModel =
                sysml2Model(name = "L") {
                    portDef(name = "P")
                    partDef(name = "A") { port(name = "o", typeId = "P") }
                }
            val legacyJson = json.encodeToString(Sysml2Model.serializer(), legacyModel)
            legacyJson shouldNotContain "isConjugated"
            val back = json.decodeFromString(Sysml2Model.serializer(), legacyJson)
            val port = back.usages.filterIsInstance<PortUsage>().single()
            port.direction.shouldBeNull()
            port.isConjugated shouldBe false
            (back.definitions.first { it is PartDefinition }).id shouldBe "A"
        }
    })
