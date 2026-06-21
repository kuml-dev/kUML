@file:Suppress("unused")

import dev.kuml.core.dsl.stateDiagram
import dev.kuml.uml.dsl.finalState
import dev.kuml.uml.dsl.initialState
import dev.kuml.uml.dsl.state
import dev.kuml.uml.dsl.transition

/**
 * DAP Verfassung — Amendment-Lebenszyklus (State Machine, V3.0.6 Chain-Showcase).
 */
stateDiagram("DAP Verfassungs-Amendment – Lebenszyklus") {
    val start = initialState()
    val vorgeschlagen = state("VORGESCHLAGEN")
    val eingereicht = state("EINGEREICHT")
    val inDiskussion = state("IN_DISKUSSION")
    val abstimmungOffen = state("ABSTIMMUNG_OFFEN")
    val angenommen = state("ANGENOMMEN")
    val angewendet = state("ANGEWENDET")
    val abgelehnt = state("ABGELEHNT")
    val angewendetFinal = finalState("ANGEWENDET_FINAL")

    transition(start, vorgeschlagen)
    transition(vorgeschlagen, eingereicht) {
        trigger = "einreichen(autor)"
        guard = "[unterstuetzer >= minUnterstuetzer]"
    }
    transition(eingereicht, inDiskussion) { trigger = "annehmenZurDiskussion()" }
    transition(inDiskussion, abstimmungOffen) { trigger = "after(diskussionsfrist)" }
    transition(abstimmungOffen, angenommen) {
        trigger = "after(abstimmungsfrist)"
        guard = "[abstimmung.beteiligung() >= quorum and abstimmung.jaAnteil() > mehrheit]"
    }
    transition(abstimmungOffen, abgelehnt) {
        trigger = "after(abstimmungsfrist)"
        guard = "[abstimmung.beteiligung() < quorum or abstimmung.jaAnteil() <= mehrheit]"
    }
    transition(angenommen, angewendet) { trigger = "anwenden()" }
    transition(angewendet, angewendetFinal) { trigger = "after(karenzfrist)" }
}
