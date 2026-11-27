import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Zustandsautomat Schleusentür") {
    val start = stateDef(name = "Start", isInitial = true)
    val zGeschlossen = stateDef(name = "Geschlossen")
    val zFrei = stateDef(name = "Geschlossen freigegeben", parent = zGeschlossen)
    val zInterlock = stateDef(name = "Geschlossen gesperrt Interlock", parent = zGeschlossen)
    val zOffen = stateDef(name = "Offen", entryAction = "Türkontakt meldet offen")
    val zStoerung = stateDef(name = "Gesperrt Störung", entryAction = "Störmeldung, Fluchttüren benutzen")
    val zEntriegelt = stateDef(name = "Entriegelt Spannungsausfall", entryAction = "Tür elektrisch entsperrt (Fail-Safe)")

    transition(name = "init", source = start, target = zFrei)
    transition(name = "T45", source = zFrei, target = zInterlock, trigger = "Gegentür wird geöffnet", effect = "sperren()", showName = true)
    transition(name = "T46", source = zInterlock, target = zFrei, trigger = "Gegentür geschlossen", guard = prose(text = "Wartezeit/Spülzeit abgelaufen"), showName = true)
    transition(name = "T47", source = zFrei, target = zOffen, trigger = "Person öffnet Tür", showName = true)
    transition(name = "T48", source = zOffen, target = zFrei, trigger = "Tür vollständig geschlossen", showName = true)
    val t49 = transition(name = "T49", source = zGeschlossen, target = zStoerung, trigger = "Türkontaktfehler", showName = true)
    val t50 = transition(name = "T50", source = zGeschlossen, target = zStoerung, trigger = "Raumdruck-Hauptalarm", guard = prose(text = "nur Tür zum Sauberen Raum"), showName = true, id = "T50")
    transition(name = "T51", source = zStoerung, target = zFrei, trigger = "ANNAHME: Störung behoben", showName = true)
    transition(name = "T52", source = zFrei, target = zEntriegelt, trigger = "Spannungsausfall", effect = "entsperren()", showName = true)
    val t53 = transition(name = "T53", source = zInterlock, target = zEntriegelt, trigger = "Spannungsausfall", effect = "entsperren()", showName = true)
    transition(name = "T54", source = zEntriegelt, target = zFrei, trigger = "ANNAHME: Spannungswiederkehr", showName = true)
    transition(name = "T49a", source = zOffen, target = zStoerung, trigger = "ANNAHME: Türkontaktfehler", showName = true, id = "T49a")
    transition(name = "T50a", source = zOffen, target = zStoerung, trigger = "ANNAHME: Raumdruck-Hauptalarm", guard = prose(text = "nur Tür zum Sauberen Raum"), showName = true, id = "T50a")
    transition(name = "T52a", source = zOffen, target = zEntriegelt, trigger = "ANNAHME: Spannungsausfall", showName = true, id = "T52a")
    transition(name = "T52b", source = zStoerung, target = zEntriegelt, trigger = "ANNAHME: Spannungsausfall", showName = true, id = "T52b")

    comment(text = "WIDERSPRUCH **A1**: T50 Sperre durch Vitec oder FMCS (2.4 gegen 3.1.1); Raumdruck nur im FMCS auswertbar (FUBE 3.5.2)", t50, id = "a1-58", kind = NoteKind.ERROR)
    comment(text = "ANNAHME **A1-E**: FMCS-Kanal Türsperrung (I1) ist ein zusätzlicher Sperrbefehl; der Interlock bleibt bei der Vitec (FUBE 2.4, 3.1.1)", t50, id = "a1e-58", kind = NoteKind.ASSUMPTION)
    comment(text = "WIDERSPRUCH **A3**: T53 Fail-Safe entriegelt, damit entfällt der Interlock (FUBE 3.5.2 gegen 3.1.1)", t53, id = "a3-58", kind = NoteKind.ERROR)
    comment(text = "OFFEN **A3**: Fluchttüren nicht definiert; Gegentür bei Türkontaktfehler und Akteur offen (FUBE 3.5.2)", t49, id = "a3-58-2", kind = NoteKind.QUESTION)
    comment(text = "SICHERHEIT **A3** / ANNAHME **A15-E**: Fail-Safe, Tür passiv entsperrt; je Tür eine elektromagnetische Sperre (FUBE 3.5.2)", zEntriegelt, id = "a15e-58", kind = NoteKind.WARNING)
    comment(text = "ANNAHME **A9-E**: T49 und T50 gelten für Interlock-Sperre wie für freigegeben; Rückkehr T51, T54 und Ausgangszustand Offen bei T49a, T50a, T52a, T52b (FUBE 3.5.2)", zGeschlossen, id = "a9e-58", kind = NoteKind.ASSUMPTION)
    comment(text = "MODELLHILFE **M2** / **M3**: Start-Pseudozustand und Rahmen Geschlossen nicht in der Quelle; je Tür eine Instanz (FUBE 3.1.2)", start, id = "m3-58", kind = NoteKind.HINT)

    stmDiagram(name = "Zustände der Schleusentür") {
        include(state = start)
        include(state = zGeschlossen)
        include(state = zFrei)
        include(state = zInterlock)
        include(state = zOffen)
        include(state = zStoerung)
        include(state = zEntriegelt)
        layout {
            direction = Down
            labelMaxWidth = 200
        }
    }
}
