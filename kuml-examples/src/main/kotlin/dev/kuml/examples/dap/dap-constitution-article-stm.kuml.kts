@file:Suppress("unused")

import dev.kuml.core.dsl.stateDiagram
import dev.kuml.uml.dsl.finalState
import dev.kuml.uml.dsl.initialState
import dev.kuml.uml.dsl.state
import dev.kuml.uml.dsl.transition

/**
 * DAP Verfassung — Artikel-Lebenszyklus (State Machine, V3.0.6 Chain-Showcase).
 */
stateDiagram("DAP Verfassungsartikel – Lebenszyklus") {
    val start = initialState()
    val entwurf = state("ENTWURF")
    val eingereicht = state("EINGEREICHT")
    val inDiskussion = state("IN_DISKUSSION")
    val abstimmungOffen = state("ABSTIMMUNG_OFFEN")
    val angenommen = state("ANGENOMMEN")
    val inKraft = state("IN_KRAFT")
    val abgelehnt = state("ABGELEHNT")
    val ausserKraft = finalState("AUSSER_KRAFT")

    transition(start, entwurf)
    transition(entwurf, eingereicht) { trigger = "einreichen(autor)" }
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
    transition(angenommen, inKraft) { trigger = "after(karenzfrist)" }
    transition(inKraft, ausserKraft) { trigger = "aufheben(amendment)" }
    transition(eingereicht, entwurf) { trigger = "zurueckziehen()" }
    transition(inDiskussion, entwurf) { trigger = "zurueckziehen()" }
}
