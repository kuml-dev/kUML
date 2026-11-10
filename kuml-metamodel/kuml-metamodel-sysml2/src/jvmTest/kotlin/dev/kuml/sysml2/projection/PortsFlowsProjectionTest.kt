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

        test("ibd projection: owner frame always drawn (Nacharbeit 47), flows carried by connector") {
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
            sysml2IbdProjection(model = legacy, diagram = IbdDiagram(name = "i", ownerId = "S")).showFrame shouldBe true
        }

        test("bdd projection: composition, aggregation, hidden features") {
            val m = vehicleModel()
            val d = m.definitions.map { it.id }
            val proj = sysml2BddProjection(model = m, diagram = dev.kuml.sysml2.BdDiagram(name = "b", elementIds = d))
            proj.edges.map { it.id } shouldContain "comp:Vehicle::engine"
            proj.edges.map { it.id } shouldContain "agg:Vehicle::tank"
            proj.hiddenFeatureIds shouldContain "Vehicle::engine"
            // flows carry the flow-property name and its TYPE: `<dir> <name> : <Type>` (conjugation flips the direction)
            proj.flowsByDef["Tank"] shouldBe listOf("in outlet.fuel : Fuel", "out outlet.exhaust : Fuel")
            proj.flowsByDef["Engine"] shouldBe listOf("out injector.fuel : Fuel", "in injector.exhaust : Fuel")
            // BDD showing only Vehicle keeps all lines (targets invisible)
            val only = sysml2BddProjection(model = m, diagram = dev.kuml.sysml2.BdDiagram(name = "b", elementIds = listOf("Vehicle")))
            only.edges.shouldBeEmpty()
            only.hiddenFeatureIds shouldBe setOf("Vehicle::flow1", "Vehicle::fuelIn")
        }

        test("bdd box content: direction word appears exactly once, flows replace the old signals compartment") {
            val m =
                sysml2Model(name = "B") {
                    attributeDef(name = "Mass")
                    portDef(name = "FuelPort") { flowProperty(name = "fuel", typeId = "Mass", direction = PortDirection.In) }
                    partDef(name = "Engine") { port(name = "out", typeId = "FuelPort", direction = PortDirection.Out) }
                }
            val proj =
                sysml2BddProjection(model = m, diagram = dev.kuml.sysml2.BdDiagram(name = "b", elementIds = listOf("Engine", "Mass")))
            val content = sysml2BddBoxContent(model = m, def = m.definitions.first { it.id == "Engine" }, projection = proj)
            // the port row is replaced by the flow line, which names the port (Nacharbeit 52)
            content.featureLines shouldBe emptyList()
            content.flows shouldBe listOf("in out.fuel : Mass")
            content.flows.none { it == "Mass" } shouldBe true
        }

        test("bdd projection: a port without flow properties keeps its row, a port with flows is hidden (Nacharbeit 52)") {
            val m =
                sysml2Model(name = "B") {
                    attributeDef(name = "Mass")
                    portDef(name = "FuelPort") { flowProperty(name = "fuel", typeId = "Mass", direction = PortDirection.In) }
                    portDef(name = "PlainPort")
                    partDef(name = "Pump") {
                        port(name = "fuelIn", typeId = "FuelPort", direction = PortDirection.In)
                        port(name = "ctrl", typeId = "PlainPort", direction = PortDirection.In)
                    }
                }
            val proj =
                sysml2BddProjection(
                    model = m,
                    diagram = dev.kuml.sysml2.BdDiagram(name = "b", elementIds = listOf("Pump", "Mass", "FuelPort", "PlainPort")),
                )
            proj.flowsByDef["Pump"] shouldBe listOf("in fuelIn.fuel : Mass")
            proj.hiddenFeatureIds shouldContain "Pump::fuelIn"
            proj.hiddenFeatureIds.contains("Pump::ctrl") shouldBe false
            val content = sysml2BddBoxContent(model = m, def = m.definitions.first { it.id == "Pump" }, projection = proj)
            content.featureLines shouldBe listOf("in ctrl : PlainPort")
        }

        test("bdd projection: multiplicity 1 is an end label on composition and reference edges (Nacharbeit 52)") {
            val m =
                sysml2Model(name = "M") {
                    val lock = partDef(name = "DoorLock")
                    val ctl = partDef(name = "Ctl")
                    val door =
                        partDef(name = "Door") {
                            part(name = "lock", typeId = lock.id)
                            refPart(name = "ctl", typeId = ctl.id)
                        }
                    bdd(name = "B") {
                        include(door)
                        include(lock)
                        include(ctl)
                    }
                }
            val d = m.diagrams.filterIsInstance<dev.kuml.sysml2.BdDiagram>().single()
            val edges = sysml2BddProjection(model = m, diagram = d).edges.associateBy { it.id }
            edges.getValue("comp:Door::lock").endLabel shouldBe "1"
            edges.getValue("agg:Door::ctl").endLabel shouldBe "1"
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
