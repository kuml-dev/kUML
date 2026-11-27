import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Zustandsautomat Türverriegelung") {
    // MODELLHILFE M2: Start-Pseudozustand
    val start = stateDef(name = "Start", isInitial = true)

    val zBeideTuerenZu = stateDef(
        name = "Beide Türen zu",
        entryAction = "Gegentüren freigegeben",
    )
    val zTuerKorridorOffen = stateDef(
        name = "Tür Korridor offen",
        entryAction = "Tür Mixing elektromagnetisch gesperrt",
    )
    val zTuerMixingOffen = stateDef(
        name = "Tür Mixing offen",
        entryAction = "Tür Korridor elektromagnetisch gesperrt",
    )
    val zStoerungBeideTuerenOffen = stateDef(
        name = "Störung Beide Türen offen",
        entryAction = "Störmeldung Beide Türen gleichzeitig offen an FMCS; optischer Alarm an der Schleuse; FMCS-Aktion: Rückluftklappe hält Position",
        doAction = "Prüfung und Behebung durch Bedienpersonal",
    )
    val zWartezeitSpuelzeit = stateDef(
        name = "Wartezeit Spülzeit",
        entryAction = "Gegentür bleibt gesperrt",
        doAction = "Wartezeit/Spülzeit läuft, Dauer nicht spezifiziert",
    )

    transition(name = "init", source = start, target = zBeideTuerenZu)

    transition(
        name = "T6", source = zTuerKorridorOffen, target = zStoerungBeideTuerenOffen,
        trigger = "Beide offen", effect = "Störmeldung erzeugen", showName = true,
    )
    transition(
        name = "T7", source = zTuerMixingOffen, target = zStoerungBeideTuerenOffen,
        trigger = "Beide offen", effect = "Störmeldung erzeugen", showName = true,
    )
    transition(
        name = "T8", source = zBeideTuerenZu, target = zStoerungBeideTuerenOffen,
        trigger = "Beide offen", effect = "Störmeldung erzeugen", showName = true,
    )
    transition(
        name = "T3", source = zTuerKorridorOffen, target = zWartezeitSpuelzeit,
        trigger = "Korridor zu", effect = "Wartezeit starten", showName = true,
    )
    transition(
        name = "T4", source = zTuerMixingOffen, target = zWartezeitSpuelzeit,
        trigger = "Mixing zu", effect = "Wartezeit starten", showName = true,
    )
    transition(
        name = "T5", source = zWartezeitSpuelzeit, target = zBeideTuerenZu,
        trigger = "Wartezeit um", guard = prose(text = "Tür vollständig zu"),
        effect = "Gegentür freigeben", showName = true,
    )
    transition(
        name = "T1", source = zBeideTuerenZu, target = zTuerKorridorOffen,
        trigger = "Korridor öffnet", guard = prose(text = "Tür Mixing zu"),
        effect = "Gegentür sperren", showName = true,
    )
    transition(
        name = "T2", source = zBeideTuerenZu, target = zTuerMixingOffen,
        trigger = "Mixing öffnet", guard = prose(text = "Tür Korridor zu"),
        effect = "Gegentür sperren", showName = true,
    )
    // ANNAHME A9-E: Ausgang der Störung ist in der Quelle nicht beschrieben
    val t9 = transition(
        name = "T9", source = zStoerungBeideTuerenOffen, target = zWartezeitSpuelzeit,
        trigger = "ANNAHME: Behoben", guard = prose(text = "Quittiert, Türen zu"),
        effect = "Wartezeit starten", showName = true,
    )

    comment(
        text = "WIDERSPRUCH **A1** / ANNAHME A1-E: Vitec sperrt die Gegentür (FUBE 3.1.1), FMCS-Kanal Türsperrung I1 (FUBE 2.4) als zusätzlicher Befehl",
        zTuerMixingOffen, id = "a1-50", kind = NoteKind.ERROR,
    )
    comment(
        text = "ANNAHME **A9-E**: Ausgang aus der Störung und Quittierung nicht beschrieben (FUBE 3.1.3)",
        t9, id = "a9e-50", kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "OFFEN **A10**: Beide Türen offen fehlt in Tabelle 3.5.2; Entstehung trotz Unterbindung unbeschrieben (FUBE 3.1.1, 3.5.2)",
        zStoerungBeideTuerenOffen, id = "a10-50", kind = NoteKind.QUESTION,
    )
    comment(
        text = "RISIKO **R23**: Beide Türen gleichzeitig offen ist sicherheitsrelevant, unverzügliche Prüfung erforderlich (FUBE 3.1.3)",
        zStoerungBeideTuerenOffen, id = "risk-50", kind = NoteKind.RISK,
    )
    comment(
        text = "MODELLHILFE **M2**: Start-Pseudozustand mit init, in der Quelle nicht vorhanden",
        start, id = "m2-50", kind = NoteKind.HINT,
    )
    comment(
        text = "MODELLHILFE **M3**: Tür Korridor und Tür Mixing stehen für Tür Korridor/Schleuse bzw. Tür Schleuse/Sauberer Raum",
        zTuerKorridorOffen, id = "m3-50", kind = NoteKind.HINT,
    )

    stmDiagram(name = "Türverriegelung Schleusenzyklus") {
        include(state = start)
        include(state = zBeideTuerenZu)
        include(state = zTuerKorridorOffen)
        include(state = zTuerMixingOffen)
        include(state = zWartezeitSpuelzeit)
        include(state = zStoerungBeideTuerenOffen)
        layout {
            direction = Down
            sameLayer(zTuerKorridorOffen, zTuerMixingOffen)
        }
    }
}
