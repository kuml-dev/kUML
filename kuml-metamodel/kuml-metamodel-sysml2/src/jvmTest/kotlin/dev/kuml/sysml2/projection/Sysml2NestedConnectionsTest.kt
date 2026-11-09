package dev.kuml.sysml2.projection

import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.constraint.Sysml2IbdChecker
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.IbdEdgeAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Nacharbeit 46: connections declared in nested part definitions (N-IBD-B), a port and an attribute of one name
 * (N-IBD-D) and connection definitions of one name (N-IBD-E).
 */
class Sysml2NestedConnectionsTest :
    FunSpec(body = {
        fun ibd(owner: String = "Anlage") = IbdDiagram(name = "d", ownerId = owner)

        fun plant(twice: Boolean = false): Sysml2Model =
            sysml2Model(name = "Anlage") {
                val signal = connectionDef(name = "Signal")
                val port = portDef(name = "SignalPort")
                val regler = partDef(name = "Regler") { port(name = "ein", typeId = port.id, direction = PortDirection.In) }
                val logik =
                    partDef(name = "Logik") {
                        port(name = "ein", typeId = port.id, direction = PortDirection.In)
                        port(name = "befehl", typeId = port.id, direction = PortDirection.Out)
                        part(name = "regler", typeId = regler.id)
                        connect(name = "l1", typeId = signal.id, sourceEndId = "ein", targetEndId = "regler.ein")
                        connect(name = "l2", typeId = signal.id, sourceEndId = "befehl", targetEndId = "regler.ein")
                    }
                val steuerung =
                    partDef(name = "Steuerung") {
                        part(name = "logik", typeId = logik.id)
                        if (twice) part(name = "reserve", typeId = logik.id)
                    }
                val anlage = partDef(name = "Anlage") { part(name = "steuerung", typeId = steuerung.id) }
                ibd(name = "Anlage", owner = anlage)
            }

        test("N-IBD-B: connections of a nested definition become one wire per instance with instance-path ends") {
            val m = plant()
            val p = sysml2IbdProjection(model = m, diagram = ibd())
            p.connections.map { it.usage.id } shouldContainExactly
                listOf("Anlage::steuerung::logik::l1", "Anlage::steuerung::logik::l2")
            val l1 = p.connections.first()
            l1.usage.sourceEndId shouldBe "Anlage::steuerung::logik::ein"
            l1.usage.targetEndId shouldBe "Anlage::steuerung::logik::regler::ein"
            l1.source.nodeId shouldBe "Anlage::steuerung"
            l1.source.steps shouldContainExactly listOf("logik", "ein")
            l1.target.steps shouldContainExactly listOf("logik", "regler", "ein")
            l1.target.terminalPort?.name shouldBe "ein"
            p.showFrame shouldBe true
        }

        test("N-IBD-B: a definition used twice gives two wires per connection with different ids") {
            val p = sysml2IbdProjection(model = plant(twice = true), diagram = ibd())
            p.connections.map { it.usage.id }.filter { it.endsWith("::l1") } shouldContainExactly
                listOf("Anlage::steuerung::logik::l1", "Anlage::steuerung::reserve::l1")
            p.connections shouldHaveSize 4
        }

        test("N-IBD-B: a connection declared in the owner by path and the one in the definition are both drawn") {
            val m =
                sysml2Model(name = "Anlage") {
                    val signal = connectionDef(name = "Signal")
                    val port = portDef(name = "SignalPort")
                    val regler = partDef(name = "Regler") { port(name = "ein", typeId = port.id) }
                    val logik =
                        partDef(name = "Logik") {
                            port(name = "ein", typeId = port.id)
                            part(name = "regler", typeId = regler.id)
                            connect(name = "l1", typeId = signal.id, sourceEndId = "ein", targetEndId = "regler.ein")
                        }
                    val steuerung = partDef(name = "Steuerung") { part(name = "logik", typeId = logik.id) }
                    val anlage =
                        partDef(name = "Anlage") {
                            part(name = "steuerung", typeId = steuerung.id)
                            connect(
                                name = "direkt",
                                typeId = signal.id,
                                sourceEndId = "steuerung.logik.ein",
                                targetEndId = "steuerung.logik.regler.ein",
                            )
                        }
                    ibd(name = "Anlage", owner = anlage)
                }
            val p = sysml2IbdProjection(model = m, diagram = ibd())
            p.connections shouldHaveSize 2
            val adapter = IbdEdgeAdapter(model = m, diagram = ibd())
            (adapter.metadataFor("conn:Anlage::direkt") != null) shouldBe true
            (adapter.metadataFor("conn:Anlage::steuerung::logik::l1") != null) shouldBe true
        }

        test("N-IBD-B: a connection with a hidden end produces no wire and no exception") {
            val m = plant()
            val hidden = IbdDiagram(name = "d", ownerId = "Anlage", elementIds = listOf("Anlage::gibtsNicht"))
            sysml2IbdProjection(model = m, diagram = hidden).connections.shouldBeEmpty()
        }

        test("N-IBD-B: the edge metadata of a nested connection equals the one of an owner connection of that definition") {
            val m = plant()
            val a = IbdEdgeAdapter(model = m, diagram = ibd()).metadataFor("conn:Anlage::steuerung::logik::l1")!!
            a.label shouldBe "l1 : Signal"
        }

        fun clash(attributeFirst: Boolean): Sysml2Model =
            sysml2Model(name = "M") {
                val port = portDef(name = "SignalPort")
                val num = attributeDef(name = "Wert")
                val regler = partDef(name = "Regler") { port(name = "ein", typeId = port.id) }
                val logik =
                    partDef(name = "Logik") {
                        if (attributeFirst) attribute(name = "stellsignal", typeId = num.id)
                        port(name = "stellsignal", typeId = port.id, direction = PortDirection.Out)
                        if (!attributeFirst) attribute(name = "stellsignal", typeId = num.id)
                        part(name = "regler", typeId = regler.id)
                    }
                val owner =
                    partDef(name = "Anlage") {
                        part(name = "logik", typeId = logik.id)
                        part(name = "regler", typeId = regler.id)
                        connect(name = "c", typeId = port.id, sourceEndId = "logik.stellsignal", targetEndId = "regler.ein")
                    }
                ibd(name = "Anlage", owner = owner)
            }

        for (attributeFirst in listOf(true, false)) {
            test("N-IBD-D: a port resolves as terminal port when the attribute of that name is declared first = $attributeFirst") {
                val m = clash(attributeFirst)
                val p = sysml2IbdProjection(model = m, diagram = ibd())
                p.connections shouldHaveSize 1
                p.connections
                    .single()
                    .source.terminalPort
                    ?.name shouldBe "stellsignal"
            }
        }

        test("N-IBD-D: IBD502 warns once about the clash, and not without one") {
            val issues = Sysml2IbdChecker.check(model = clash(attributeFirst = true), diagram = ibd()).filter { it.code == 502 }
            issues shouldHaveSize 1
            issues.single().message shouldContain "Port 'stellsignal' and attribute 'stellsignal' in 'Logik' share a name"
            Sysml2IbdChecker.check(model = plant(), diagram = ibd()).filter { it.code == 502 }.shouldBeEmpty()
        }

        fun twoSignals(): Sysml2Model =
            sysml2Model(name = "M") {
                val plain = connectionDef(name = "Analog 0-10 V")
                val dashed =
                    connectionDef(name = "Analog 0-10 V", style = dev.kuml.sysml2.ConnectionLineStyle.DASHED, color = "#1D2968")
                val port = portDef(name = "SignalPort")
                val a = partDef(name = "Regler") { port(name = "aus", typeId = port.id) }
                val b = partDef(name = "Klappe") { port(name = "ein", typeId = port.id) }
                val owner =
                    partDef(name = "Anlage") {
                        part(name = "regler", typeId = a.id)
                        part(name = "klappe", typeId = b.id)
                        part(name = "klappe2", typeId = b.id)
                        connect(name = "c1", typeId = plain.id, sourceEndId = "regler.aus", targetEndId = "klappe.ein")
                        connect(name = "c2", typeId = dashed.id, sourceEndId = "regler.aus", targetEndId = "klappe2.ein")
                    }
                ibd(name = "Anlage", owner = owner)
            }

        test("N-IBD-E: two connection definitions of one name get different ids and keep the model name") {
            val m = twoSignals()
            val defs = m.definitions.filterIsInstance<dev.kuml.sysml2.ConnectionDefinition>()
            defs.map { it.name } shouldContainExactly listOf("Analog 0-10 V", "Analog 0-10 V")
            (defs[0].id != defs[1].id) shouldBe true
            val adapter = IbdEdgeAdapter(model = m, diagram = ibd())
            adapter.metadataFor("conn:Anlage::c1")!!.dashArray shouldBe null
            adapter.metadataFor("conn:Anlage::c2")!!.dashArray shouldBe "6 4"
            adapter.metadataFor("conn:Anlage::c1")!!.label shouldBe "c1 : Analog 0-10 V"
            adapter.metadataFor("conn:Anlage::c2")!!.label shouldBe "c2 : Analog 0-10 V"
        }

        test("N-IBD-E: an explicit id is kept") {
            val m = sysml2Model(name = "M") { connectionDef(name = "A", id = "x1") }
            m.definitions.single().id shouldBe "x1"
        }

        test("N-IBD-E: IBD503 warns about same-named definitions used in the diagram only") {
            val owner = IbdDiagram(name = "d", ownerId = "Anlage")
            val issues = Sysml2IbdChecker.check(model = twoSignals(), diagram = owner).filter { it.code == 503 }
            issues shouldHaveSize 1
            issues.single().message shouldContain "'Analog 0-10 V' are defined more than once"
            Sysml2IbdChecker.check(model = plant(), diagram = ibd()).filter { it.code == 503 }.shouldBeEmpty()
        }
    })
