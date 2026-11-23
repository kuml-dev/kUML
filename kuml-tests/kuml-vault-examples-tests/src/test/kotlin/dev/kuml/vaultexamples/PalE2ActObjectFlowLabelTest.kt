@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL wave E2 (19c): the label of an object flow (`<<Typ>>`) sits on its own line: the distance between the label
 * rectangle and the polyline of the same edge is at most 6 px. Measured on the SVG (no font dependency).
 */
class PalE2ActObjectFlowLabelTest :
    FunSpec({
        test("object flow labels sit at most 6 px from their own line") {
            val r = VaultExampleRenderer.render(script = SCRIPT)
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "pal-e2/act-objectflow.svg", content = svg)
            val labels = SvgGeometry.labelRects(svg, "kuml-edge-label")
            val lines = SvgGeometry.edgePolylines(svg)
            val objectLabels = labels.filter { it.first.startsWith("objectFlow:") }
            objectLabels.size shouldBe 2
            for ((id, rect) in objectLabels) {
                val own = lines[id]!!
                val d = SvgGeometry.distance(own, rect)
                (d <= 6.0) shouldBe true
            }
        }
    }) {
    companion object {
        private val SCRIPT =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model(name = "Personendurchgang") {

                partDef(name = "Person")
                partDef(name = "Schleusensteuerung Vitec")
                partDef(name = "FMCS")

                val bahnPerson = activityPartition(name = "Person", represents = "Person")
                val bahnVitec = activityPartition(name = "Schleusensteuerung Vitec", represents = "Schleusensteuerung Vitec")
                val bahnFmcs = activityPartition(name = "FMCS", represents = "FMCS")

                val start = initialNode(partition = bahnPerson)

                val oeffnen = actionDef(name = "Person öffnet Tür Korridor", partition = bahnPerson)
                val schliessen = actionDef(name = "Person schließt Tür Korridor", partition = bahnPerson)

                val kontaktOffen = acceptEvent(name = "Türkontakt Korridor offen", partition = bahnVitec)
                val sperre = sendSignal(name = "Sperre Gegentür Mixing", signal = "Gegentür sperren", partition = bahnVitec)
                val kontaktZu = acceptEvent(name = "Türkontakt Korridor geschlossen", partition = bahnVitec)
                val joinV = joinNode(name = "Vereinigung Sperre und Schließen", partition = bahnVitec)
                val warte = actionDef(name = "Wartezeit Spülzeit abwarten", partition = bahnVitec)
                val frei = sendSignal(name = "Freigabe Gegentür Mixing", signal = "Gegentür freigeben", partition = bahnVitec)
                val endeV = flowFinalNode(partition = bahnVitec)

                val halten = actionDef(name = "Rückluftklappe halten, Druckregelung aussetzen", partition = bahnFmcs)
                val einfrieren = callBehavior(
                    name = "Einfrieren VSR und Klappenantriebe",
                    activityId = "Aktivität Einfrierlogik VSR und Klappenantriebe",
                    partition = bahnFmcs,
                )
                val joinF = joinNode(name = "Vereinigung Einfrieren und Schließen", partition = bahnFmcs)
                val timer = acceptTimeEvent(name = "Verzögerungszeit abgelaufen", time = "after 10 s", partition = bahnFmcs)
                val regel = actionDef(name = "Regelung PAL freigeben", partition = bahnFmcs)
                val ende = flowFinalNode(partition = bahnFmcs)

                controlFlow(name = "f01", source = start, target = oeffnen)
                controlFlow(name = "f02", source = oeffnen, target = kontaktOffen)
                controlFlow(name = "f03", source = oeffnen, target = schliessen)
                controlFlow(name = "f04", source = kontaktOffen, target = sperre)
                objectFlow(name = "o01", source = kontaktOffen, target = halten, objectType = "Türzustand")
                controlFlow(name = "f05", source = halten, target = einfrieren)
                controlFlow(name = "f06", source = schliessen, target = kontaktZu)
                controlFlow(name = "f07", source = kontaktZu, target = joinV)
                controlFlow(name = "f08", source = sperre, target = joinV)
                controlFlow(name = "f09", source = joinV, target = warte)
                controlFlow(name = "f10", source = warte, target = frei)
                controlFlow(name = "f10b", source = frei, target = endeV)
                objectFlow(name = "o02", source = kontaktZu, target = joinF, objectType = "Türzustand")
                controlFlow(name = "f11", source = einfrieren, target = joinF)
                controlFlow(name = "f12", source = joinF, target = timer)
                controlFlow(name = "f13", source = timer, target = regel)
                controlFlow(name = "f14", source = regel, target = ende)


                actDiagram(name = "Personendurchgang Korridor in die Schleuse") {
                    include(node = start)
                    include(node = oeffnen)
                    include(node = schliessen)
                    include(node = kontaktOffen)
                    include(node = sperre)
                    include(node = kontaktZu)
                    include(node = joinV)
                    include(node = warte)
                    include(node = frei)
                    include(node = endeV)
                    include(node = halten)
                    include(node = einfrieren)
                    include(node = joinF)
                    include(node = timer)
                    include(node = regel)
                    include(node = ende)
                }
            }

            """.trimIndent()
    }
}
