@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Reconstruction of the PAL "Doc 51" state machine (a composite state with several children, transitions T10..T20 with
 * long labels, parallel edges in one corridor). The original model is not part of the repository or the vault as a
 * script; this fixture reproduces the structure from its description. Built with the Kotlin builder because the
 * bridge module has no script host.
 */
internal object Doc51Fixture {
    fun model(right: Boolean = false): Sysml2Model =
        sysml2Model(name = "Doc51") {
            val start = stateDef(name = "Start", isInitial = true)
            val offen = stateDef(name = "Offen")
            val gehalten = stateDef(name = "Gehalten", entryAction = "halten()", exitAction = "freigeben()")
            val a = stateDef(name = "Regeln", parent = gehalten)
            val b = stateDef(name = "Warten", parent = gehalten)
            val c = stateDef(name = "Pruefen", parent = gehalten)
            val d = stateDef(name = "Sperren", parent = gehalten)
            val zu = stateDef(name = "Geschlossen")
            transition(name = "T10", source = start, target = offen)
            transition(name = "T11", source = offen, target = a, trigger = "ANNAHME: Tuer offen und Druck stabil", showName = true)
            transition(
                name = "T12",
                source = a,
                target = b,
                trigger = "ANNAHME: Klappe haelt Position",
                effect = "Regelung aussetzen",
                showName = true,
            )
            transition(
                name = "T19",
                source = b,
                target = a,
                trigger = "ANNAHME: Tuer geschlossen und Klappe frei",
                effect = "Regelung wieder aufnehmen",
                showName = true,
            )
            transition(name = "T13", source = a, target = c, trigger = "Wartezeit abgelaufen", showName = true)
            transition(name = "T20", source = c, target = a, trigger = "Abbruch durch Bediener", showName = true)
            transition(name = "T14", source = c, target = d, guard = "Druck zu hoch", showName = true)
            transition(name = "T17", source = b, target = d, trigger = "Sperre anfordern", showName = true)
            transition(name = "T15", source = d, target = zu, trigger = "Freigabe", showName = true)
            transition(name = "T16", source = zu, target = offen, trigger = "Oeffnen", showName = true)
            stmDiagram("Doc51") {
                layout { direction = if (right) Right else Down }
                include(start)
                include(offen)
                include(gehalten)
                include(a)
                include(b)
                include(c)
                include(d)
                include(zu)
            }
        }

    fun layout(right: Boolean = false): Pair<LayoutGraph, LayoutResult> {
        val m = model(right)
        val d = m.diagrams.filterIsInstance<StmDiagram>().single()
        val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
        val r =
            LayoutSpecRunner
                .layout(
                    engine = ElkLayoutEngine(),
                    graph = g,
                    hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d),
                    spec = d.layout,
                    kind = SpecDiagramKind.SYSML2_STM,
                ).result
        return g to r
    }
}
