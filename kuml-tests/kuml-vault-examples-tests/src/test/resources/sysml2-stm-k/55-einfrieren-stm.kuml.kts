import dev.kuml.sysml2.dsl.sysml2Model
import kotlin.time.Duration.Companion.seconds

sysml2Model(name = "Zustandsautomat Einfrieren VSR und Klappenantriebe") {
    // Modellierungshilfe (M2): Initialzustand, die Quelle nennt keinen Startzustand
    val start = stateDef(name = "Start", isInitial = true)

    // S21: VSR, Klappenantriebe und Druckregelung in PAL (und Mixing) aktiv (FUBE 3.6)
    val zFreigegeben = stateDef(name = "Regelung freigegeben")

    // S22: Tür Korridor zur PAL offen, VSR und Klappenantriebe in der PAL eingefroren (FUBE 3.6.1)
    val zEingefrorenPal = stateDef(name = "Eingefroren PAL", doAction = "Druckregelung in PAL ausgesetzt")

    // S23: Tür PAL zu Mixing offen, VSR und Klappenantriebe in PAL und Mixing-Raum eingefroren (FUBE 3.6.2)
    val zEingefrorenPalMixing = stateDef(
        name = "Eingefroren PAL und Mixing",
        doAction = "Druckregelung in PAL und Mixing ausgesetzt",
    )

    // S24: Tür wieder zu, Freigabe erst nach Ablauf der Verzögerungszeit (FUBE 3.6.1, 3.6.2)
    // Bewusst ohne Do-Text: Halten der Position während der Verzögerung nennt die Quelle nicht
    val zVerzoegerung = stateDef(name = "Verzögerung Freigabe ausstehend")

    transition(name = "init", source = start, target = zFreigegeben, showName = true)

    // T33 bis T37: Quelle (FUBE 3.6.1, 3.6.2)
    transition(
        name = "T33", source = zFreigegeben, target = zEingefrorenPal,
        trigger = "Tür Korridor geöffnet", effect = "Einfrieren PAL", showName = true,
    )
    transition(
        name = "T34", source = zFreigegeben, target = zEingefrorenPalMixing,
        trigger = "Tür Mixing geöffnet", effect = "Einfrieren PAL und Mixing", showName = true,
    )
    transition(
        name = "T35", source = zEingefrorenPal, target = zVerzoegerung,
        trigger = "Tür Korridor geschlossen", effect = "Timer 10 s starten", showName = true,
    )
    transition(
        name = "T36", source = zEingefrorenPalMixing, target = zVerzoegerung,
        trigger = "Tür Mixing geschlossen", effect = "Timer 10 s starten", showName = true,
    )
    transition(
        name = "T37", source = zVerzoegerung, target = zFreigegeben,
        timer = after(duration = 10.seconds), effect = "Regelung freigeben", showName = true,
    )

    // T55, T56: Annahme A4-E / A9-E, Wiederöffnen in der Verzögerung, Timer verfällt
    val t55 = transition(
        name = "T55", source = zVerzoegerung, target = zEingefrorenPal,
        trigger = "ANNAHME: Korridor erneut geöffnet", effect = "Timer verwerfen", showName = true,
    )
    transition(
        name = "T56", source = zVerzoegerung, target = zEingefrorenPalMixing,
        trigger = "ANNAHME: Mixing erneut geöffnet", effect = "Timer verwerfen", showName = true,
    )

    comment(
        text = "ANNAHME **A4-E** / **A9-E**: 10 s gelten für beide Türen; Wiederöffnen verwirft den Timer (FUBE 3.6.1, 3.6.2)",
        t55, id = "a4e-55", kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "OFFEN **A4**: Zeit fest, mindestens 10 s oder parametriert; Halten der Position offen (FUBE 3.2.2, 3.5.1, 3.6)",
        zVerzoegerung, id = "a4-55", kind = NoteKind.QUESTION,
    )
    comment(
        text = "ANNAHME **A5-E**: Ausführender des Einfrierens ist das FMCS, in der Quelle nicht genannt (FUBE 3.6)",
        zFreigegeben, id = "a5e-55", kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "OFFEN **A5**: VSR nur in 2.1 gelistet, Klappenantrieb in keiner Tabelle (2.2, 2.4); Verhältnis zur RLK offen (FUBE 2.1, 3.6)",
        zEingefrorenPal, id = "a5-55", kind = NoteKind.QUESTION,
    )
    comment(
        text = "MODELLHILFE **M2**: Start-Pseudozustand und init; Anfangszustand ist Annahme (FUBE 3.6)",
        start, id = "m2-55", kind = NoteKind.HINT,
    )

    stmDiagram(name = "Zustände der VSR- und Klappen-Einfrierung") {
        include(state = start)
        include(state = zFreigegeben)
        include(state = zEingefrorenPal)
        include(state = zEingefrorenPalMixing)
        include(state = zVerzoegerung)

        // Gilt für den ganzen Automaten, deshalb freistehend
        comment(
            text = "MODELLHILFE **M3**: Nebenläufig zu STM 50 und 51; Quelle beschreibt kein Zusammenspiel; Türnamen verkürzt (FUBE 3.6)",
            id = "m3-55", kind = NoteKind.HINT,
        )
        layout {
            direction = Down
            labelMaxWidth = 130
        }
    }
}
