---
tags: [kUML, beispiel, journey, user-journey, pdv]
status: aktiv
date: 2026-06-24
---

# 34 User Journey – PdV Mitglieder-Journey

**User Journey** (vereinfachte Customer-Perspektive) der PdV-Mitglieder-Journey —
nur die Kundensicht mit Touchpoints und Emotion-Kurve, ohne die organisationsinternen
Backstage- und Support-Schichten.

Im Unterschied zu [[33 Blueprint – PdV Mitglieder-Journey]] (volles Service Blueprint
nach Shostack mit allen vier Schichten) zeigt diese Ansicht ausschließlich was der
Interessent erlebt: welche Schritte er durchläuft, über welche Kanäle er interagiert
und wie er sich dabei fühlt.

Sechs Phasen: **Entdeckung → Interessenbekundung → Antragstellung →
Beitrittsprozess → Willkommen → Erstes Engagement**.

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

## Erwartetes Bild

Eine einzelne Customer-Actions-Zeile mit sechs Phasen-Spalten, Touchpoint-Symbolen
mit Channel-Icons und der Emotion-Kurve darüber — NEUTRAL → POSITIVE → NEUTRAL
(mit Pain-Markierung) → NEGATIVE-Tal (Wartezeit) → VERY_POSITIVE → VERY_POSITIVE.

Kein Backstage, kein Support. Geeignet als schlanke Präsentationsfolie für
Stakeholder, die nur die Kundenperspektive interessiert.

Verwandt: [[33 Blueprint – PdV Mitglieder-Journey]], [[00 Übersicht]], [[V3.1-User-Journey-Wellenplan]].
