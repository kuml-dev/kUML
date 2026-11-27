import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Zustandsautomat Betriebsart Schleusensteuerung") {
    val start = stateDef(name = "Start", isInitial = true)

    val zNormalbetrieb = stateDef(name = "Normalbetrieb")
    val zLokalerAutomatikbetrieb = stateDef(
        name = "Lokaler Automatikbetrieb",
        entryAction = "Störmeldung; Funktionsumfang nicht definiert",
    )
    val zSpannungsausfall = stateDef(
        name = "Spannungsausfall",
        entryAction = "Türen entriegelt Fail-Safe, elektrisch entsperrt; Störmeldung; Türsperrung Nein",
    )

    transition(name = "init", source = start, target = zNormalbetrieb)

    val t40 = transition(
        name = "T40",
        source = zNormalbetrieb,
        target = zLokalerAutomatikbetrieb,
        trigger = "Kommunikationsfehler",
        showName = true,
    )
    val t41 = transition(
        name = "T41",
        source = zLokalerAutomatikbetrieb,
        target = zNormalbetrieb,
        trigger = "ANNAHME: Komm. zurück",
        showName = true,
    )
    val t42 = transition(
        name = "T42",
        source = zNormalbetrieb,
        target = zSpannungsausfall,
        trigger = "Spannungsausfall",
        showName = true,
    )
    val t43 = transition(
        name = "T43",
        source = zLokalerAutomatikbetrieb,
        target = zSpannungsausfall,
        trigger = "Spannungsausfall",
        showName = true,
    )
    val t44 = transition(
        name = "T44",
        source = zSpannungsausfall,
        target = zNormalbetrieb,
        trigger = "ANNAHME: Spannung zurück",
        showName = true,
    )

    comment(
        text = "WIDERSPRUCH **A1**: Türsperrung nur Vitec (FUBE 3.1.1), aber FMCS liefert Türsperrung an Vitec (FUBE 2.4)",
        zNormalbetrieb,
        id = "a1-57",
        kind = NoteKind.ERROR,
    )
    comment(
        text = "WIDERSPRUCH **A3**: Fail-Safe entriegelt die Türen, Interlock entfällt (FUBE 3.5.2 gegen 3.1.1)",
        zSpannungsausfall,
        id = "a3-57",
        kind = NoteKind.ERROR,
    )
    comment(
        text = "SICHERHEIT **A3**: Türen elektrisch entsperrt, Türsperrung Nein (FUBE 3.5.2)",
        t42,
        id = "a3s-57",
        kind = NoteKind.WARNING,
    )
    comment(
        text = "OFFEN **A14**: lokaler Automatikbetrieb nicht definiert; Erkennung bei Hardwarekontakten, Spannungsausfall ohne Objekt, übergeordnet gegen dezentral (FUBE 2.1, 2.3, 3.5.2)",
        zLokalerAutomatikbetrieb,
        id = "a14-57",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "OFFEN **A19**: Ausfall des FMCS selbst nicht beschrieben (FUBE 3.5.1)",
        t43,
        id = "a19-57",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "ANNAHME **A9-E**: Rückkehr T41 und T44 in Normalbetrieb, Quelle nennt nur den Eintritt (FUBE 3.5.2)",
        t41,
        id = "a9e-57",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "MODELLHILFE **M2** / **M4**: Start-Pseudozustand erfunden; Normalbetrieb in der Quelle implizit",
        start,
        id = "m24-57",
        kind = NoteKind.HINT,
    )

    stmDiagram(name = "Betriebsart der Schleusensteuerung") {
        include(state = start)
        include(state = zNormalbetrieb)
        include(state = zLokalerAutomatikbetrieb)
        include(state = zSpannungsausfall)
        layout {
            direction = Down
        }
    }
}
