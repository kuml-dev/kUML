import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Aktivität Störungsbehandlung Beide Türen offen") {
    // MODELLHILFE: Typen für die Bahnen (Komponenten C6, C5, C24)
    partDef(name = "Schleusensteuerung Vitec")
    partDef(name = "FMCS")
    partDef(name = "Bedienpersonal")

    val bahnVitec = activityPartition(name = "Schleusensteuerung Vitec", represents = "Schleusensteuerung Vitec")
    val bahnFmcs = activityPartition(name = "FMCS", represents = "FMCS")
    val bahnBp = activityPartition(name = "Bedienpersonal", represents = "Bedienpersonal")

    // F6 Beide Türen gleichzeitig offen (3.1.3, 3.5.1; fehlt in Tabelle 3.5.2, A10)
    val start = initialNode(partition = bahnVitec)
    val ausloeser = acceptEvent(name = "Beide Türkontakte melden offen", partition = bahnVitec)
    val msgBeide = sendSignal(name = "Störmeldung Beide Türen gleichzeitig offen", signal = "Störmeldung", partition = bahnVitec)
    val alarm = actionDef(name = "Optischen Alarm auslösen", partition = bahnVitec)
    val rlk = actionDef(name = "Rückluftklappe bleibt in Position", partition = bahnFmcs)
    val pruefen = actionDef(name = "Prüfen und beheben, unverzüglich", partition = bahnBp)
    val ende = finalNode(partition = bahnFmcs)
    val flussende = flowFinalNode(partition = bahnFmcs)

    controlFlow(name = "f01", source = start, target = ausloeser)
    controlFlow(name = "f02", source = ausloeser, target = msgBeide)
    controlFlow(name = "f03", source = msgBeide, target = alarm)
    controlFlow(name = "f04", source = alarm, target = rlk)
    controlFlow(name = "f05", source = rlk, target = pruefen)
    controlFlow(name = "f06", source = pruefen, target = ende)

    // I2: Störmeldungen Schleusensteuerung an FMCS (Tabelle 2.4)
    objectFlow(name = "o1", source = msgBeide, target = flussende, objectType = "Störmeldung")

    comment(
        text = "HINWEIS **R23**: Beide Türen gleichzeitig offen ist sicherheitsrelevant (FUBE 3.1.3)",
        msgBeide, id = "r23-62", kind = NoteKind.HINT,
        placement = NotePlacement.ABOVE,
    )
    comment(
        text = "ANNAHME **A9-E**: Vitec erzeugt die Störmeldung (FUBE 2.4, 3.1.2)",
        msgBeide, id = "a9e-62", kind = NoteKind.ASSUMPTION,
        placement = NotePlacement.LEFT,
    )
    comment(
        text = "ANNAHME **A2-E**: Alarm von der Vitec ausgelöst, an der Ampel stellvertretend (FUBE 3.1.3)",
        alarm, id = "a2e-62", kind = NoteKind.ASSUMPTION,
        placement = NotePlacement.BELOW,
    )
    comment(
        text = "ANNAHME **A5-E**: Klappe über das FMCS-Stellsignal gehalten (FUBE 3.2.1, 3.1.3)",
        rlk, id = "a5e-62", kind = NoteKind.ASSUMPTION,
        placement = NotePlacement.BELOW,
    )
    comment(
        text = "OFFEN **A10** / **A9**: Fall fehlt in Tabelle 3.5.2; Türsperrung, Ausgang, Alarmklasse offen (FUBE 3.5.1)",
        pruefen, id = "a10-62", kind = NoteKind.QUESTION,
        placement = NotePlacement.BELOW,
    )
    comment(
        text = "Legende: ANNAHME (umgekehrtes Dreieck) = Modellannahme, OFFEN (Sprechblase) = Lücke der Quelle, HINWEIS (Glühbirne) = Hinweis; Start, Ende, Flussende, Ereignisknoten und Reihenfolge sind Modellhilfen (**M2**); IDs siehe Offene Punkte",
        ende, id = "legende", kind = NoteKind.INFO,
        placement = NotePlacement.BELOW,
    )

    actDiagram(name = "Störungsbehandlung Beide Türen offen") {
        include(node = start)
        include(node = ausloeser)
        include(node = msgBeide)
        include(node = alarm)
        include(node = rlk)
        include(node = pruefen)
        include(node = ende)
        include(node = flussende)
        layout {
            direction = Down
        }
    }
}
