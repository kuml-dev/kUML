@file:Suppress("unused")

import dev.kuml.core.dsl.stateDiagram
import dev.kuml.uml.dsl.finalState
import dev.kuml.uml.dsl.initialState
import dev.kuml.uml.dsl.state
import dev.kuml.uml.dsl.transition

/**
 * DAP Verfassung — Amendment-Lebenszyklus (State Machine, V3.0.6 Chain-Showcase).
 */
stateDiagram(name = "DAP Verfassungs-Amendment – Lebenszyklus") {
    val start = initialState()
    val vorgeschlagen = state(name = "VORGESCHLAGEN")
    val eingereicht = state(name = "EINGEREICHT")
    val inDiskussion = state(name = "IN_DISKUSSION")
    val abstimmungOffen = state(name = "ABSTIMMUNG_OFFEN")
    val angenommen = state(name = "ANGENOMMEN")
    val angewendet = state(name = "ANGEWENDET")
    val abgelehnt = state(name = "ABGELEHNT")
    val angewendetFinal = finalState(name = "ANGEWENDET_FINAL")

    transition(source = start, target = vorgeschlagen)
    transition(source = vorgeschlagen, target = eingereicht) {
        trigger = "einreichen(autor)"
        guard = "[unterstuetzer >= minUnterstuetzer]"
    }
    transition(source = eingereicht, target = inDiskussion) { trigger = "annehmenZurDiskussion()" }
    transition(source = inDiskussion, target = abstimmungOffen) { trigger = "after(diskussionsfrist)" }
    transition(source = abstimmungOffen, target = angenommen) {
        trigger = "after(abstimmungsfrist)"
        guard = "[abstimmung.beteiligung() >= quorum and abstimmung.jaAnteil() > mehrheit]"
    }
    transition(source = abstimmungOffen, target = abgelehnt) {
        trigger = "after(abstimmungsfrist)"
        guard = "[abstimmung.beteiligung() < quorum or abstimmung.jaAnteil() <= mehrheit]"
    }
    transition(source = angenommen, target = angewendet) { trigger = "anwenden()" }
    transition(source = angewendet, target = angewendetFinal) { trigger = "after(karenzfrist)" }
}
