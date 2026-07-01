package dev.kuml.sysml2.edge

import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * V2.0.13 — PAR bindings render solid with no arrow head; seit V2.x trägt
 * jede Binding-Edge den [BindingConnectorUsage.name] als Plain-Label,
 * sodass im Diagramm erkennbar bleibt, welcher Pin an welches Attribut
 * koppelt, solange Pin-Endpoint-Anchoring noch out of scope ist.
 */
class ParEdgeAdapterTest :
    StringSpec({

        val diagram = ParDiagram(name = "F=ma", elementIds = listOf("Vehicle", "NewtonsLaw"))

        "binding edge carries name as plain label, solid line, no arrow head" {
            val binding =
                BindingConnectorUsage(
                    id = "binding:NewtonsLaw::m::Vehicle::mass",
                    name = "m_to_mass",
                    sourceEndId = "NewtonsLaw::m",
                    targetEndId = "Vehicle::mass",
                )
            val model = Sysml2Model(name = "F=ma", usages = listOf(binding))
            val meta = ParEdgeAdapter(model, diagram).metadataFor("binding:NewtonsLaw::m::Vehicle::mass")!!
            meta.stereotype.shouldBeNull()
            meta.label shouldBe "m_to_mass"
            meta.dashArray.shouldBeNull()
            meta.arrowHead shouldBe Sysml2ArrowHead.None
        }

        "anonymous binding falls back to `<srcPin> = <tgtPin>` label" {
            val binding =
                BindingConnectorUsage(
                    id = "binding:NewtonsLaw::F::Vehicle::force",
                    name = "",
                    sourceEndId = "NewtonsLaw::F",
                    targetEndId = "Vehicle::force",
                )
            val model = Sysml2Model(name = "F=ma", usages = listOf(binding))
            val meta = ParEdgeAdapter(model, diagram).metadataFor("binding:NewtonsLaw::F::Vehicle::force")!!
            meta.label shouldBe "F = force"
        }

        "fully anonymous binding (no name, no `::` in endpoints) has null label" {
            val binding =
                BindingConnectorUsage(
                    id = "binding:foo::bar",
                    name = "",
                    sourceEndId = "",
                    targetEndId = "",
                )
            val model = Sysml2Model(name = "F=ma", usages = listOf(binding))
            val meta = ParEdgeAdapter(model, diagram).metadataFor("binding:foo::bar")!!
            meta.label.shouldBeNull()
        }

        "unknown edge id returns null" {
            val model = Sysml2Model(name = "F=ma")
            ParEdgeAdapter(model, diagram).metadataFor("missing").shouldBeNull()
        }
    })
