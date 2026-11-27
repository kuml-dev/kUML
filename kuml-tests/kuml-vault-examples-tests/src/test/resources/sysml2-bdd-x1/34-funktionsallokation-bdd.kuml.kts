import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Funktionsallokation Schleusensteuerung Vitec und FMCS") {
    // Belegte Funktionen (Zeilen im actions-Abschnitt der Steuerungsblöcke)
    val fVerriegelung = actionDef(name = "Türverriegelung")
    val fTuerzustand = actionDef(name = "Türzustand überwachen")
    val fRaumdruck = actionDef(name = "Raumdruck regeln")
    val fPartikel = actionDef(name = "Partikelwerte empfangen")
    val fErhitzer = actionDef(name = "E-Erhitzer regeln")

    // Strittige Funktionen (eigene Aktionsblöcke mit allocate)
    val sperrung = actionDef(name = "Türsperrung (I1)")
    val einfrieren = actionDef(name = "Einfrieren VSR und Klappenantriebe")
    val ampel = actionDef(name = "Ampel treiben")
    val alarm = actionDef(name = "Optischen Alarm auslösen")
    val stoermeldung = actionDef(name = "Störmeldung erzeugen")

    // Steuerungen (C6, C5); I1 und I2 als gerichtete Verbindungen (refPart)
    val vitec = partDef(name = "Schleusensteuerung Vitec") {
        action(name = "Türverriegelung", typeId = fVerriegelung.id)
        action(name = "Türzustand überwachen", typeId = fTuerzustand.id)
        refPart(name = "I2 Türzustand, Störmeldung", typeId = "FMCS")
    }
    val fmcs = partDef(name = "FMCS") {
        action(name = "Raumdruck regeln", typeId = fRaumdruck.id)
        action(name = "Partikelwerte empfangen", typeId = fPartikel.id)
        action(name = "E-Erhitzer regeln", typeId = fErhitzer.id)
        refPart(name = "I1 Türsperrung", typeId = vitec.id)
    }

    // WIDERSPRUCH A1: Türsperrung an beide Steuerungen
    allocate(sperrung.id, vitec.id)
    allocate(sperrung.id, fmcs.id)
    // ANNAHMEN: A5-E, A7-E (FMCS), A2-E, A9-E (Vitec)
    allocate(einfrieren.id, fmcs.id)
    allocate(ampel.id, fmcs.id)
    allocate(alarm.id, vitec.id)
    allocate(stoermeldung.id, vitec.id)

    comment(
        text = "WIDERSPRUCH **A1** / ANNAHME **A1-E**: Verriegelung nur Vitec (FUBE 3.1.1), Türsperrung FMCS zu Vitec (FUBE 2.4); I1 als zusätzlicher Sperrbefehl, unentschieden",
        sperrung,
        id = "a1-34",
        kind = NoteKind.ERROR,
    )
    comment(
        text = "ANNAHME **A2-E** / **A7-E**: FMCS treibt die Ampel (I9), Vitec löst den optischen Alarm aus (I10); nicht belegt, FUBE 2.1 ist nur eine Komponentenliste",
        ampel,
        alarm,
        id = "a2e7e-34",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A5-E**: FMCS friert VSR und Klappenantriebe ein (I11); FUBE 3.6 nennt keinen Ausführenden",
        einfrieren,
        id = "a5e-34",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A9-E**: Störmeldung nur von der Vitec erzeugt (I18), FMCS empfängt sie (I2); Empfänger und Alarmklasse offen (FUBE 3.1.3, 3.5.3)",
        stoermeldung,
        id = "a9e-34",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A13-E**: Strömungswächter und STB wirken auf E-Erhitzer und FMCS (Freigabe); beide Lesarten offen (FUBE 3.7.2, 3.7.3)",
        fmcs,
        id = "a13e-34",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "MODELLHILFE **M2**: Funktionen als Aktionsdefinitionen und I1/I2 als refPart sind Hilfskonstrukte; die Funktionsliste steht so nicht in der Quelle",
        vitec,
        id = "m2-34",
        kind = NoteKind.HINT,
    )
    comment(
        text = "OFFEN **A1** / **A14**: Vitec ist „übergeordnet\" (FUBE 2.1), aber „dezentral\" unter dem übergeordneten FMCS (FUBE 2.3)",
        vitec,
        id = "a14-34",
        kind = NoteKind.QUESTION,
    )

    bdd(name = "Funktionsallokation") {
        include(definition = vitec)
        include(definition = fmcs)
        include(definition = sperrung)
        include(definition = einfrieren)
        include(definition = ampel)
        include(definition = alarm)
        include(definition = stoermeldung)
        layout {
            direction = Up
        }
    }
}
