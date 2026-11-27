import dev.kuml.sysml2.dsl.sysml2Model

// PAL Welle X3 fixture: synthesised from the building blocks of PAL documents 62 and 67 (a decision / merge version).
// The gate test replaces the include marker below to vary the include order.
sysml2Model(name = "Störungsbehandlung kombiniert") {
    partDef(name = "Schleusensteuerung Vitec")
    partDef(name = "FMCS")
    partDef(name = "Bedienpersonal")

    val bahnVitec = activityPartition(name = "Schleusensteuerung Vitec", represents = "Schleusensteuerung Vitec")
    val bahnFmcs = activityPartition(name = "FMCS", represents = "FMCS")
    val bahnBp = activityPartition(name = "Bedienpersonal", represents = "Bedienpersonal")

    val start = initialNode(partition = bahnVitec)
    val vorlauf = acceptEvent(name = "Welche Störung erkannt", partition = bahnVitec)
    val wahl = decisionNode(name = "Störungsart", partition = bahnVitec)

    // F6 branch (document 62)
    val msgBeide = sendSignal(name = "Störmeldung Beide Türen gleichzeitig offen", signal = "Störmeldung", partition = bahnVitec)
    val alarm = actionDef(name = "Optischen Alarm auslösen", partition = bahnVitec)
    val rlk = actionDef(name = "Rückluftklappe bleibt in Position", partition = bahnFmcs)

    // F1 branch (document 67)
    val msgTuer = sendSignal(name = "Störmeldung Türkontaktfehler", signal = "Störmeldung", partition = bahnVitec)
    val sperren = actionDef(name = "Betroffene Tür sperren", partition = bahnVitec)
    val flucht = actionDef(name = "Fluchttüren benutzen", partition = bahnBp)

    val sammler = mergeNode(name = "Zusammenführung", partition = bahnBp)
    val pruefen = actionDef(name = "Prüfen und beheben, unverzüglich", partition = bahnBp)
    val ende = finalNode(partition = bahnFmcs)
    val flussende = flowFinalNode(partition = bahnFmcs)

    controlFlow(name = "f01", source = start, target = vorlauf)
    controlFlow(name = "f02", source = vorlauf, target = wahl)
    controlFlow(name = "f03", source = wahl, target = msgBeide, guard = "beide Türen offen")
    controlFlow(name = "f04", source = msgBeide, target = alarm)
    controlFlow(name = "f05", source = alarm, target = rlk)
    controlFlow(name = "f06", source = rlk, target = sammler)
    controlFlow(name = "f07", source = wahl, target = msgTuer, guard = "Türkontaktfehler")
    controlFlow(name = "f08", source = msgTuer, target = sperren)
    controlFlow(name = "f09", source = sperren, target = flucht)
    controlFlow(name = "f10", source = flucht, target = sammler)
    controlFlow(name = "f11", source = sammler, target = pruefen)
    controlFlow(name = "f12", source = pruefen, target = ende)

    objectFlow(name = "o1", source = msgBeide, target = flussende, objectType = "Störmeldung")
    objectFlow(name = "o2", source = msgTuer, target = flussende, objectType = "Störmeldung")

    comment(text = "HINWEIS **R23**: Beide Türen gleichzeitig offen ist sicherheitsrelevant", msgBeide, id = "r23", kind = NoteKind.HINT, placement = NotePlacement.ABOVE)
    comment(text = "ANNAHME **A9-E**: Vitec erzeugt die Störmeldung", msgBeide, id = "a9e", kind = NoteKind.ASSUMPTION, placement = NotePlacement.LEFT)
    comment(text = "ANNAHME **A2-E**: Alarm von der Vitec ausgelöst", alarm, id = "a2e", kind = NoteKind.ASSUMPTION, placement = NotePlacement.BELOW)
    comment(text = "ANNAHME **A1-E**: Vitec sperrt die betroffene Tür", sperren, id = "a1e", kind = NoteKind.ASSUMPTION, placement = NotePlacement.BELOW)
    comment(text = "OFFEN **A10**: Fall fehlt in Tabelle 3.5.2", pruefen, id = "a10", kind = NoteKind.QUESTION, placement = NotePlacement.BELOW)
    comment(
        text = "Legende: ANNAHME = Modellannahme, OFFEN = Lücke der Quelle, HINWEIS = Hinweis; Start, Ende, Flussende und Reihenfolge sind Modellhilfen",
        ende, id = "legende", kind = NoteKind.INFO, placement = NotePlacement.BELOW,
    )

    actDiagram(name = "Störungsbehandlung kombiniert") {
//INCLUDES//
        layout {
            direction = Down
        }
    }
}
