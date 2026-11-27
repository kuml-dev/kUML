import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Zustandsautomat Störmeldeklassen") {
    val start = stateDef(name = "Start", isInitial = true)

    val zKeineStoermeldung = stateDef(
        name = "Keine Störmeldung",
        entryAction = "Kein Alarm aktiv (Normalbetrieb)",
    )

    val zVoralarm = stateDef(
        name = "Voralarm",
        entryAction = "Warnung: Hinweis auf abweichende Betriebszustände",
        doAction = "Überwachung durch Bedienpersonal erforderlich",
    )

    val zHauptalarm = stateDef(
        name = "Hauptalarm",
        entryAction = "Störung: kritische Abweichung",
        doAction = "Türsperrung oder Einschränkung des Schleusenbetriebs",
    )

    val zSicherheitsabschaltung = stateDef(
        name = "Sicherheitsabschaltung",
        entryAction = "Sofortige Reaktion ohne Verzögerung",
        doAction = "Kritische Störmeldung an das FMCS",
    )

    transition(name = "init", source = start, target = zKeineStoermeldung)

    val t23 = transition(
        name = "T23",
        source = zKeineStoermeldung,
        target = zVoralarm,
        trigger = "ANNAHME: abweichender Betriebszustand erkannt",
        showName = true,
    )

    transition(
        name = "T25",
        source = zVoralarm,
        target = zHauptalarm,
        trigger = "ANNAHME: Eskalation",
        showName = true,
    )

    val t24 = transition(
        name = "T24",
        source = zKeineStoermeldung,
        target = zHauptalarm,
        trigger = "kritische Abweichung",
        guard = prose(text = "Raumdruckschwelle nicht spezifiziert"),
        showName = true,
    )

    transition(
        name = "T26",
        source = zKeineStoermeldung,
        target = zSicherheitsabschaltung,
        trigger = "ANNAHME: sicherheitskritisches Ereignis",
        showName = true,
    )

    transition(
        name = "T27",
        source = zHauptalarm,
        target = zKeineStoermeldung,
        trigger = "ANNAHME: Störung behoben",
        showName = true,
    )

    comment(
        text = "ANNAHME **A6-E**: Sollwertbereich (3.4), Toleranzbereich (3.5.1) und Hauptalarm-Schwelle (3.5.2) sind dieselbe Schwelle",
        t24,
        id = "a6e-53",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A9-E**: T23 vollständig Annahme (Klasse belegt in 3.5.3, Auslöser nicht); T25 bis T27 ebenso",
        t23,
        id = "a9e-53",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "OFFEN **A9**: Voralarm-Auslöser fehlt; Empfänger: Sicherheitsabschaltung FMCS, Voralarm Bedienpersonal, Hauptalarm keiner (3.5.3)",
        zVoralarm,
        id = "a9-53",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "OFFEN **A9**: Türsperrung oder Einschränkung des Schleusenbetriebs (3.5.3) gegen Tabelle 3.5.2 ohne Klasse; Einschränkung undefiniert",
        zHauptalarm,
        id = "a9-53-2",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "OFFEN **A10**: Beide Türen offen hat keine Alarmklasse und keine Zeile in Tabelle 3.5.2 (3.1.3, 3.5.1)",
        zHauptalarm,
        id = "a10-53",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "RISIKO **S16**: Sicherheitsabschaltung, sofortige Reaktion ohne Verzögerung, kritische Störmeldung an das FMCS (3.5.3)",
        zSicherheitsabschaltung,
        id = "risk-53",
        kind = NoteKind.RISK,
    )
    comment(
        text = "MODELLHILFE **M2** / **M4**: Start-Pseudozustand mit init-Übergang; Zustand Keine Störmeldung ist in der Quelle implizit",
        zKeineStoermeldung,
        id = "m2m4-53",
        kind = NoteKind.HINT,
    )

    stmDiagram(name = "Zustände der Störmeldeklassen") {
        include(state = start)
        include(state = zKeineStoermeldung)
        include(state = zVoralarm)
        include(state = zHauptalarm)
        include(state = zSicherheitsabschaltung)
        layout {
            direction = Down
            first(start)
            sameLayer(zVoralarm, zSicherheitsabschaltung)
        }
    }
}
