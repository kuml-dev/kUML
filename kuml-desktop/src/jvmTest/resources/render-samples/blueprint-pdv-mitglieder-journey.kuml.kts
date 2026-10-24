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
