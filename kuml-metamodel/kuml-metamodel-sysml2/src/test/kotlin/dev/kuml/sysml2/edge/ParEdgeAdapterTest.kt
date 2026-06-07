package dev.kuml.sysml2.edge

import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * V2.0.13 — PAR bindings carry no label / no stereotype, render solid
 * with no arrow head. The adapter exists for **symmetry** so the SVG /
 * LaTeX renderers dispatch through one uniform code path.
 */
class ParEdgeAdapterTest :
    StringSpec({

        val diagram = ParDiagram(name = "F=ma", elementIds = listOf("Vehicle", "NewtonsLaw"))

        "binding edge has solid line, no label, no arrow head" {
            val binding =
                BindingConnectorUsage(
                    id = "binding:NewtonsLaw::m::Vehicle::mass",
                    name = "b1",
                    sourceEndId = "NewtonsLaw::m",
                    targetEndId = "Vehicle::mass",
                )
            val model = Sysml2Model(name = "F=ma", usages = listOf(binding))
            val meta = ParEdgeAdapter(model, diagram).metadataFor("binding:NewtonsLaw::m::Vehicle::mass")!!
            meta.stereotype.shouldBeNull()
            meta.label.shouldBeNull()
            meta.dashArray.shouldBeNull()
            meta.arrowHead shouldBe Sysml2ArrowHead.None
        }

        "unknown edge id returns null" {
            val model = Sysml2Model(name = "F=ma")
            ParEdgeAdapter(model, diagram).metadataFor("missing").shouldBeNull()
        }
    })
