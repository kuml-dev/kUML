package dev.kuml.sysml2.projection

import dev.kuml.sysml2.ConnectionDirection
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/** Nacharbeit 47: IBD attribute values, connection direction, derived flow carrier, owner frame (metamodel level). */
class Sysml2IbdWaveMTest :
    FunSpec(body = {

        fun plant(
            connections: Int,
            viaOf: Int? = null,
            direction: ConnectionDirection = ConnectionDirection.NONE,
            withFlow: Boolean = true,
        ): Sysml2Model =
            sysml2Model(name = "Anlage") {
                val medium = partDef(name = "Medium")
                val link = connectionDef(name = "Leitung")
                val port = portDef(name = "Anschluss")
                val quelle = partDef(name = "Quelle") { port(name = "aus", typeId = port.id, direction = PortDirection.Out) }
                val senke = partDef(name = "Senke") { port(name = "ein", typeId = port.id, direction = PortDirection.In) }
                val anlage =
                    partDef(name = "Anlage") {
                        part(name = "quelle", typeId = quelle.id)
                        part(name = "senke", typeId = senke.id)
                        val made =
                            (1..connections).map {
                                connect(
                                    name = "c$it",
                                    typeId = link.id,
                                    sourceEndId = "quelle.aus",
                                    targetEndId = "senke.ein",
                                    direction = direction,
                                )
                            }
                        if (withFlow) {
                            flow(item = medium, from = "quelle.aus", to = "senke.ein", via = viaOf?.let { made[it - 1] })
                        }
                    }
                ibd(name = "Anlage", owner = anlage)
            }

        fun projection(m: Sysml2Model) = sysml2IbdProjection(model = m, diagram = m.diagrams.filterIsInstance<IbdDiagram>().single())

        test("carrierOf: explicit via names the carrier") {
            val p = projection(plant(connections = 2, viaOf = 2))
            p.carrierOf.values.single() shouldBe "Anlage::c2"
            p.ownLineFlows.size shouldBe 0
        }

        test("carrierOf: no via and exactly one matching connection derives the carrier") {
            val p = projection(plant(connections = 1))
            p.carrierOf.values.single() shouldBe "Anlage::c1"
            p.carriedFlows.size shouldBe 1
            p.ownLineFlows.size shouldBe 0
        }

        test("carrierOf: no matching connection keeps the own line") {
            val p = projection(plant(connections = 0))
            p.carrierOf shouldBe emptyMap()
            p.ownLineFlows.size shouldBe 1
        }

        test("carrierOf: several matching connections are ambiguous, own line stays") {
            val p = projection(plant(connections = 2))
            p.carrierOf shouldBe emptyMap()
            p.ownLineFlows.size shouldBe 1
        }

        test("showFrame is true for a resolvable owner without ports, false for an unresolvable owner") {
            val m = plant(connections = 0, withFlow = false)
            projection(m).showFrame shouldBe true
            sysml2IbdProjection(model = m, diagram = IbdDiagram(name = "x", ownerId = "Ghost")).showFrame shouldBe false
        }

        test("direction: default NONE, JSON round trip, old JSON decodes, printer round trip") {
            val m = plant(connections = 1, direction = ConnectionDirection.FORWARD, withFlow = false)
            m.usages
                .filterIsInstance<ConnectionUsage>()
                .single()
                .direction shouldBe ConnectionDirection.FORWARD
            Json.decodeFromString(Sysml2Model.serializer(), Json.encodeToString(Sysml2Model.serializer(), m)) shouldBe m
            val old =
                Json { ignoreUnknownKeys = true }.decodeFromString(
                    ConnectionUsage.serializer(),
                    """{"id":"A::c","name":"c","definitionId":"L","sourceEndId":"A::x","targetEndId":"A::y"}""",
                )
            old.direction shouldBe ConnectionDirection.NONE
            Sysml2DslPrinter.print(m) shouldContain "direction = dev.kuml.sysml2.ConnectionDirection.FORWARD"
            Sysml2DslPrinter.print(plant(connections = 1, withFlow = false)) shouldNotContain "ConnectionDirection"
        }

        test("attribute compartment values use the display form with the unit") {
            val m =
                sysml2Model(name = "Raum") {
                    val druck = attributeDef(name = "Raumdruck", unitSymbol = "Pa")
                    val temp = attributeDef(name = "Temperatur")
                    val sensor =
                        partDef(name = "Sensor") {
                            attribute(name = "messwert", typeId = druck.id, default = -5.0)
                            attribute(name = "temperatur", typeId = temp.id, defaultLiteral = "20.0[°C]")
                        }
                    val raum = partDef(name = "Raum") { part(name = "sensor", typeId = sensor.id) }
                    ibd(name = "Raum", owner = raum, showAttributes = true)
                }
            val part = m.usages.filterIsInstance<dev.kuml.sysml2.PartUsage>().single { it.name == "sensor" }
            val texts = ibdPartAttributes(model = m, usage = part).lines.map { it.text }
            texts shouldBe listOf("messwert : Raumdruck = -5${NBSP}Pa", "temperatur : Temperatur = 20.0[°C]")
            // Nacharbeit 51: a defaultLiteral is shown verbatim (no number / unit normalisation), also in the IBD.
        }

        test("direction survives on a wire that exists only in the projection (nested definition)") {
            val m =
                sysml2Model(name = "Anlage") {
                    val link = connectionDef(name = "Leitung")
                    val port = portDef(name = "Anschluss")
                    val regler = partDef(name = "Regler") { port(name = "ein", typeId = port.id, direction = PortDirection.In) }
                    val logik =
                        partDef(name = "Logik") {
                            port(name = "ein", typeId = port.id, direction = PortDirection.In)
                            part(name = "regler", typeId = regler.id)
                            connect(
                                name = "l1",
                                typeId = link.id,
                                sourceEndId = "ein",
                                targetEndId = "regler.ein",
                                direction = ConnectionDirection.FORWARD,
                            )
                        }
                    val steuerung = partDef(name = "Steuerung") { part(name = "logik", typeId = logik.id) }
                    val anlage = partDef(name = "Anlage") { part(name = "steuerung", typeId = steuerung.id) }
                    ibd(name = "Anlage", owner = anlage)
                }
            val nested = projection(m).connections.single { it.usage.id == "Anlage::steuerung::logik::l1" }
            nested.usage.direction shouldBe ConnectionDirection.FORWARD
            dev.kuml.sysml2.edge
                .IbdEdgeAdapter(model = m, diagram = m.diagrams.filterIsInstance<IbdDiagram>().single())
                .metadataFor("conn:Anlage::steuerung::logik::l1")!!
                .arrowHead shouldBe dev.kuml.sysml2.edge.Sysml2ArrowHead.OpenAngle
        }
    })
