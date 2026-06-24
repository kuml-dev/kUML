---
tags: [kUML, beispiel, blueprint, journey, service-blueprint, pdv]
status: aktiv
date: 2026-06-24
---

# 33 Blueprint – PdV Mitglieder-Journey

Voller **Service Blueprint** (nach Lynn Shostack) mit allen vier Schichten,
den drei Trennlinien und der Emotion-Kurve. Zeigt die Mitglieder-Journey der
[[Partei der Vernunft]] von der ersten Begegnung bis zum ersten Engagement —
inklusive des klassischen „Tal der Tränen" in Phase 4 (Wartezeit auf die
Aufnahme).

Sechs Phasen: **Entdeckung → Interessenbekundung → Antragstellung →
Beitrittsprozess → Willkommen → Erstes Engagement**.

> [!note] DSL-Hinweis
> Touchpoints werden wegen der `@DslMarker`-Scope-Isolation **vor** den
> `phase { }`-Blöcken deklariert (auf der `blueprint { }`-Ebene) und in den
> Steps über ihre Id referenziert.

```kuml
blueprint("PdV Mitglieder-Journey") {
    // ── Akteure ──
    val interessent = actor("Interessent", ActorRole.CUSTOMER)
    val mitgliederbuero = actor("Mitgliederbüro", ActorRole.STAFF)
    val crm = actor("Mitglieder-CRM", ActorRole.SYSTEM)
    val landesverband = actor("Landesverband", ActorRole.PARTNER)

    // ── Kanäle ──
    val web = channel("pdv.de", ChannelKind.WEB)
    val social = channel("Social Media", ChannelKind.SOCIAL)
    val mailC = channel("E-Mail", ChannelKind.EMAIL)
    val personC = channel("Stammtisch", ChannelKind.IN_PERSON)

    // ── Touchpoints (vor den Phasen deklariert) ──
    val socialPost = touchpoint("Social-Post", channel = social)
    val kampagne = touchpoint("Kampagne", channel = social)
    val programmSeite = touchpoint("Programm-Seite", channel = web)
    val newsletter = touchpoint("Newsletter-Anmeldung", channel = web)
    val onlineAntrag = touchpoint("Online-Antrag", channel = web)
    val bestaetigung = touchpoint("Bestätigungs-Mail", channel = mailC)
    val willkommensMail = touchpoint("Willkommens-Mail", channel = mailC)
    val ausweis = touchpoint("Mitgliedsausweis", channel = mailC)
    val stammtisch = touchpoint("Lokaler Stammtisch", channel = personC)
    val eventEinladung = touchpoint("Event-Einladung", channel = mailC)

    // ── Phase 1: Entdeckung ──
    phase("Entdeckung") {
        customer("Stößt auf PdV-Inhalt", Sentiment.NEUTRAL, touchpoints = listOf(socialPost))
        frontstage("Liefert Content & Werbung", actor = mitgliederbuero, touchpoints = listOf(kampagne))
        support("Trackt Reichweite", actor = crm)
    }

    // ── Phase 2: Interessenbekundung ──
    phase("Interessenbekundung") {
        customer("Besucht Website, liest Programm", Sentiment.POSITIVE, touchpoints = listOf(programmSeite))
        frontstage("Bietet Newsletter & Infomaterial", actor = mitgliederbuero, touchpoints = listOf(newsletter))
        backstage("Qualifiziert Lead", actor = mitgliederbuero)
        support("Speichert Interessent", actor = crm)
    }

    // ── Phase 3: Antragstellung ──
    phase("Antragstellung") {
        customer("Füllt Beitrittsantrag aus", Sentiment.NEUTRAL,
                 touchpoints = listOf(onlineAntrag), pain = "Formular wirkt lang")
        frontstage("Bestätigt Eingang", actor = mitgliederbuero, touchpoints = listOf(bestaetigung))
        backstage("Prüft Angaben & Beitrag", actor = mitgliederbuero)
        support("Legt Mitgliedsdatensatz an", actor = crm)
    }

    // ── Phase 4: Beitrittsprozess (Tal der Tränen) ──
    phase("Beitrittsprozess") {
        customer("Wartet auf Aufnahme", Sentiment.NEGATIVE, pain = "Unklar, wie lange es dauert")
        backstage("Beschließt Aufnahme im Vorstand", actor = mitgliederbuero)
        support("Weist Landesverband zu", actor = landesverband)
    }

    // ── Phase 5: Willkommen ──
    phase("Willkommen") {
        customer("Erhält Willkommenspaket", Sentiment.VERY_POSITIVE, touchpoints = listOf(willkommensMail))
        frontstage("Versendet Willkommenspaket", actor = mitgliederbuero, touchpoints = listOf(ausweis))
        support("Aktiviert Mitgliederbereich", actor = crm)
    }

    // ── Phase 6: Erstes Engagement ──
    phase("Erstes Engagement") {
        customer("Nimmt am Stammtisch teil", Sentiment.VERY_POSITIVE, touchpoints = listOf(stammtisch))
        frontstage("Lädt zu Veranstaltungen ein", actor = landesverband, touchpoints = listOf(eventEinladung))
        backstage("Matcht Mitglied zu lokaler Gruppe", actor = landesverband)
    }

    blueprintDiagram("PdV Service Blueprint", emotionCurve = true)
}
```

## Erwartetes Bild

Sechs Spalten (Phasen), vier horizontale Schicht-Bänder (Customer Actions,
Frontstage, Backstage, Support Processes), die drei Trennlinien (Line of
Interaction durchgezogen, Line of Visibility gestrichelt, Line of Internal
Interaction gepunktet), Touchpoint-Symbole mit Channel-Icons und eine
Emotion-Kurve, die von NEUTRAL über POSITIVE in ein NEGATIVE-Tal (Wartezeit,
Phase 4) und wieder hoch zu VERY_POSITIVE verläuft — die klassische
„Tal der Tränen"-Dramaturgie.

Verwandt: [[00 Übersicht]], [[V3.1-User-Journey-Wellenplan]].
