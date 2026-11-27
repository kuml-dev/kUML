import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Zustandsautomat E-Erhitzer") {
    // MODELLHILFE M2: Initialzustand, die Quelle nennt keinen Startzustand
    val start = stateDef(name = "Start", isInitial = true)

    // S17: nicht freigegeben, 0 V = Erhitzer aus (FUBE 3.7.3)
    val zAusNichtFreigegeben = stateDef(
        name = "Aus nicht freigegeben",
        invariant = "Stellsignal 0 V, Erhitzer aus",
    )

    // S18: Freigabe, PI-Regelung der Raumtemperatur auf 20 °C mit 0-10 V (FUBE 3.7, 3.7.3)
    val zRegelbetriebPi = stateDef(
        name = "Regelbetrieb PI",
        invariant = "Strömung ausreichend, STB nicht ausgelöst",
        doAction = "PI-Regelung Raumtemperatur auf 20 °C, 0-10 V",
    )

    // S19: Strömungswächter meldet unzureichende Luftströmung (FUBE 3.7.2)
    val zAbgeschaltetStroemung = stateDef(
        name = "Abgeschaltet Strömung unzureichend",
        invariant = "Strömung unzureichend",
    )

    // S20: Kanaltemperatur über 70 °C, STB hat abgeschaltet, Rücksetzung manuell (FUBE 3.7.2)
    val zAbgeschaltetStb = stateDef(
        name = "Abgeschaltet STB ausgelöst",
        invariant = "Kanaltemperatur über 70 °C, Rücksetzung manuell",
    )

    // MODELLHILFE M2: Übergang vom Startzustand
    transition(name = "init", source = start, target = zAusNichtFreigegeben)

    // T28 (FUBE 3.7.3): Freigabebedingungen
    transition(
        name = "T28",
        source = zAusNichtFreigegeben,
        target = zRegelbetriebPi,
        trigger = "Freigabe",
        guard = prose(text = "Strömungswächter meldet ausreichende Strömung und STB nicht ausgelöst"),
        effect = "PI-Regelung startet",
        showName = true,
    )

    // T29 (FUBE 3.7.2): Strömungswächter schaltet bei unzureichender Strömung ab
    transition(
        name = "T29",
        source = zRegelbetriebPi,
        target = zAbgeschaltetStroemung,
        trigger = "Strömungswächter meldet unzureichende Strömung",
        effect = "E-Erhitzer wird abgeschaltet",
        showName = true,
    )

    // T30 (FUBE 3.7.2): STB schaltet bei Kanaltemperatur über 70 °C ab (P10)
    transition(
        name = "T30",
        source = zRegelbetriebPi,
        target = zAbgeschaltetStb,
        trigger = "Kanaltemperatur über 70 °C",
        effect = "STB schaltet E-Erhitzer ab",
        showName = true,
    )

    // T31 (ANNAHME A9-E): automatischer Wiederanlauf, die Quelle beschreibt ihn nicht
    val t31 = transition(
        name = "T31",
        source = zAbgeschaltetStroemung,
        target = zRegelbetriebPi,
        trigger = "ANNAHME: Strömung wieder ausreichend",
        effect = "Wiederanlauf",
        showName = true,
    )

    // T32 (FUBE 3.7.2, 3.7.3): manuelle Rücksetzung des STB, Akteur nicht genannt (A3-E)
    val t32 = transition(
        name = "T32",
        source = zAbgeschaltetStb,
        target = zAusNichtFreigegeben,
        trigger = "STB manuell zurückgesetzt",
        effect = "Freigabe prüft Strömungswächter erneut",
        showName = true,
    )

    comment(
        text = "OFFEN **A13**: Wirkpfade von Strömungswächter und STB (Hardware oder FMCS) offen; Auslösung nur im Betrieb beschrieben (FUBE 3.7.2, 3.7.3)",
        zRegelbetriebPi,
        id = "a13-54-2",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "ANNAHME **A13-E**: Strömungswächter und STB wirken auf E-Erhitzer und FMCS, beide Lesarten gezeigt (FUBE 3.7.2, 3.7.3)",
        zAbgeschaltetStb,
        id = "a13e-54",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A9-E**: Wiederanlauf T31 nach Strömungsverlust ist Annahme, die Quelle beschreibt ihn nicht (FUBE 3.7.3)",
        t31,
        id = "a9e-54",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A3-E**: STB wird manuell zurückgesetzt (T32), Akteur nicht genannt, Bedienpersonal angenommen (FUBE 3.7.2)",
        t32,
        id = "a3e-54",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "MODELLHILFE **M4**: Zustand teilweise aus Freigabebedingungen und 0 V = Erhitzer aus abgeleitet (FUBE 3.7.3)",
        zAusNichtFreigegeben,
        id = "m4-54",
        kind = NoteKind.HINT,
    )
    comment(
        text = "MODELLHILFE **M2**: Start und init sind Modellierungshilfe, Anfangszustand ist Annahme",
        start,
        id = "m2-54",
        kind = NoteKind.HINT,
    )

    stmDiagram(name = "Zustände des E-Erhitzers") {
        include(state = start)
        include(state = zAusNichtFreigegeben)
        include(state = zRegelbetriebPi)
        include(state = zAbgeschaltetStb)
        include(state = zAbgeschaltetStroemung)
        layout {
            direction = Down
            labelMaxWidth = 200
            sameLayer(zAbgeschaltetStb, zAbgeschaltetStroemung)
        }
    }
}
