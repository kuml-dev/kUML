import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Zustandsautomat Druckampel") {
    // MODELLHILFE M2: Initialzustand, Quelle nennt keinen Startzustand
    val start = stateDef(name = "Start", isInitial = true)

    // S11 (FUBE 3.4)
    val zGruen = stateDef(
        name = "Grün",
        entryAction = "Normalbetrieb, Druckkaskade intakt, keine Maßnahme erforderlich",
    )

    // S12 (FUBE 3.4)
    val zRot = stateDef(
        name = "Rot",
        entryAction = "Hauptalarm, Druckkaskade gestört, sofortige Prüfung erforderlich",
    )

    transition(name = "init", source = start, target = zGruen)

    val t21 = transition(
        name = "T21",
        source = zGruen,
        target = zRot,
        trigger = "Raumdruck verlässt Sollwertbereich",
        guard = prose(text = "Schwelle nicht spezifiziert"),
        showName = true,
    )

    val t22 = transition(
        name = "T22",
        source = zRot,
        target = zGruen,
        trigger = "ANNAHME: Raumdruck wieder im Sollwertbereich",
        showName = true,
    )

    comment(
        text = "ANNAHME **A6-E**: Sollwertbereich, Toleranzbereich und Hauptalarm-Schwelle sind eine Schwelle; nicht beziffert (FUBE 3.4, 3.5.1, 3.5.2)",
        t21,
        id = "a6e-52",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A7-E**: FMCS treibt die Ampel; Rückwechsel ohne Hysterese oder Quittierung (FUBE 3.4)",
        t22,
        id = "a7e-52",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A9-E**: Rückwechsel Rot nach Grün ist Annahme, die Quelle beschreibt nur den Wechsel auf Rot (FUBE 3.4)",
        t22,
        id = "a9e-52",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "OFFEN **A2**: Ampel zweistufig, aber drei Alarmklassen; optischer Alarm aus 3.1.3 als Ampel nicht belegt (FUBE 3.4, 3.5.3)",
        zRot,
        id = "a2-52",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "OFFEN **A7**: Treiber und Signalweg der Ampel nicht beschrieben, nicht in Tabelle 2.4 (FUBE 2.2, 3.4)",
        zGruen,
        id = "a7-52",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "MODELLHILFE **M2** / **M4**: Start und init nicht in der Quelle; Anfangszustand Grün ist Annahme",
        start,
        id = "m2-52",
        kind = NoteKind.HINT,
    )

    stmDiagram(name = "Zustände der Druckampel") {
        include(state = start)
        include(state = zGruen)
        include(state = zRot)
        layout {
            direction = Down
        }
    }
}
