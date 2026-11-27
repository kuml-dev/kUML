import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Zustandsautomat Partikelmonitoring") {
    // Modellierungshilfe (M2): Initialzustand, Quelle nennt keinen Startzustand
    val start = stateDef(name = "Start", isInitial = true)

    // S25: Überwachung, Messwert unterhalb Grenzwert (implizit, FUBE 3.3.1)
    val zUeberwachung = stateDef(
        name = "Überwachung",
        doAction = "Partikelkonzentration überwachen",
    )

    // S26: Grenzwert überschritten, nur Qualitätsmeldung, keine Türsperrung (FUBE 1.2, 3.3.1)
    val zGrenzwertUeberschritten = stateDef(
        name = "Grenzwert überschritten",
        entryAction = "Qualitätsmeldung an GLT, keine Türsperrung",
    )

    transition(name = "init", source = start, target = zUeberwachung)

    // T38 (Quelle 1.2, 3.3.1); Grenzwert nicht spezifiziert, daher Freitext-Guard
    val t38 = transition(
        name = "T38",
        source = zUeberwachung,
        target = zGrenzwertUeberschritten,
        trigger = "Messwert",
        guard = prose(text = "Partikelkonzentration über Grenzwert (nicht spezifiziert)"),
        showName = true,
    )

    // T39 (ANNAHME, Quelle beschreibt den Rückwechsel nicht)
    val t39 = transition(
        name = "T39",
        source = zGrenzwertUeberschritten,
        target = zUeberwachung,
        trigger = "ANNAHME: zurück",
        showName = true,
    )

    comment(
        text = "WIDERSPRUCH **A22**: „keine Türsperrung“ (FUBE 1.2) gegen „keine zusätzliche Türsperrung“ (FUBE 3.3.1); wozu zusätzlich, ist nicht erklärt",
        zGrenzwertUeberschritten,
        id = "a22-56",
        kind = NoteKind.ERROR,
    )
    comment(
        text = "WICHTIG **R4 / R5 / R6**: nur Qualitätsmeldung an die GLT, kein Reinraum nach ISO 14644-1, ausschließlich Qualitätsdokumentation (FUBE 1.2, 3.3.1)",
        zUeberwachung,
        id = "w-56",
        kind = NoteKind.IMPORTANT,
    )
    comment(
        text = "OFFEN **A12**: Qualitätsüberwachung (FUBE 2.1, 2.2, 3.3) gegen Qualitätsdokumentation (FUBE 1.2, 3.3.1); Grenzwert nicht spezifiziert",
        zGrenzwertUeberschritten,
        id = "a12-56",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "ANNAHME **A8-E** / OFFEN **A8**: GLT = Gebäudeleittechnik, Absender FMCS abgeleitet; GLT nicht definiert (FUBE 1.3, 3.3.1)",
        zGrenzwertUeberschritten,
        id = "a8e-56",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A9-E**: Rückwechsel nach Überwachung bei Konzentration unter dem Grenzwert, in der Quelle nicht beschrieben (FUBE 3.3.1)",
        t39,
        id = "a9e-56",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A21-E** / OFFEN **A21**: generische PAL; Partikelsensor nur in ausgewählten PAL, Auswahl und Anzahl offen (FUBE 2.1, 3.3.1)",
        zUeberwachung,
        id = "a21e-56",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "MODELLHILFE **M2 / M4**: Start-Pseudozustand mit init-Übergang und impliziter Zustand Überwachung, in der Quelle nicht vorhanden",
        start,
        id = "m2-56",
        kind = NoteKind.HINT,
    )

    stmDiagram(name = "Zustände des Partikelmonitorings") {
        include(state = start)
        include(state = zUeberwachung)
        include(state = zGrenzwertUeberschritten)
        layout { direction = Down }
    }
}
