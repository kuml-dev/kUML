---
type: UserJourney
title: user-journey
---
# user-journey

```kuml
blueprint("PdV Mitglieder-Journey") {
    // ── Kanäle ──
    val web = channel("pdv.de", ChannelKind.WEB)
    val social = channel("Social Media", ChannelKind.SOCIAL)
    val mailC = channel("E-Mail", ChannelKind.EMAIL)
    val personC = channel("Stammtisch", ChannelKind.IN_PERSON)

    // ── Touchpoints (vor den Phasen deklariert) ──
    val socialPost = touchpoint("Social-Post", channel = social)
    val programmSeite = touchpoint("Programm-Seite", channel = web)
    val onlineAntrag = touchpoint("Online-Antrag", channel = web)
    val bestaetigung = touchpoint("Bestätigungs-Mail", channel = mailC)
    val willkommensMail = touchpoint("Willkommens-Mail", channel = mailC)
    val ausweis = touchpoint("Mitgliedsausweis", channel = mailC)
    val stammtisch = touchpoint("Lokaler Stammtisch", channel = personC)

    // ── Phase 1: Entdeckung ──
    phase("Entdeckung") {
        customer("Stößt auf PdV-Inhalt", Sentiment.NEUTRAL, touchpoints = listOf(socialPost))
    }

    // ── Phase 2: Interessenbekundung ──
    phase("Interessenbekundung") {
        customer("Besucht Website, liest Programm", Sentiment.POSITIVE, touchpoints = listOf(programmSeite))
    }

    // ── Phase 3: Antragstellung ──
    phase("Antragstellung") {
        customer("Füllt Beitrittsantrag aus", Sentiment.NEUTRAL,
                 touchpoints = listOf(onlineAntrag, bestaetigung), pain = "Formular wirkt lang")
    }

    // ── Phase 4: Beitrittsprozess (Tal der Tränen) ──
    phase("Beitrittsprozess") {
        customer("Wartet auf Aufnahme", Sentiment.NEGATIVE, pain = "Unklar, wie lange es dauert")
    }

    // ── Phase 5: Willkommen ──
    phase("Willkommen") {
        customer("Erhält Willkommenspaket", Sentiment.VERY_POSITIVE,
                 touchpoints = listOf(willkommensMail, ausweis))
    }

    // ── Phase 6: Erstes Engagement ──
    phase("Erstes Engagement") {
        customer("Nimmt am Stammtisch teil", Sentiment.VERY_POSITIVE, touchpoints = listOf(stammtisch))
    }

    journeyDiagram("PdV User Journey", emotionCurve = true)
}
```
