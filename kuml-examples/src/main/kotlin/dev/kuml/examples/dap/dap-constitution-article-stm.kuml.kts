@file:Suppress("unused")

import dev.kuml.core.dsl.stateDiagram
import dev.kuml.uml.dsl.finalState
import dev.kuml.uml.dsl.initialState
import dev.kuml.uml.dsl.state
import dev.kuml.uml.dsl.transition

/**
 * DAP Verfassung — Artikel-Lebenszyklus (State Machine, V3.0.6 Chain-Showcase).
 */
stateDiagram(name = "DAP Verfassungsartikel – Lebenszyklus") {
    val start = initialState()
    val entwurf = state(name = "ENTWURF")
    val eingereicht = state(name = "EINGEREICHT")
    val inDiskussion = state(name = "IN_DISKUSSION")
    val abstimmungOffen = state(name = "ABSTIMMUNG_OFFEN")
    val angenommen = state(name = "ANGENOMMEN")
    val inKraft = state(name = "IN_KRAFT")
    val abgelehnt = state(name = "ABGELEHNT")
    val ausserKraft = finalState(name = "AUSSER_KRAFT")

    transition(source = start, target = entwurf)
    transition(source = entwurf, target = eingereicht) { trigger = "einreichen(autor)" }
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
    transition(source = angenommen, target = inKraft) { trigger = "after(karenzfrist)" }
    transition(source = inKraft, target = ausserKraft) { trigger = "aufheben(amendment)" }
    transition(source = eingereicht, target = entwurf) { trigger = "zurueckziehen()" }
    transition(source = inDiskussion, target = entwurf) { trigger = "zurueckziehen()" }
}
