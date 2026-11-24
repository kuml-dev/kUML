// Fixture of PAL wave J2: the real "Doc 51" composite state machine (Rueckluftklappen-Regelung), a copy of the vault example
// 51 (Stand 2026-11-24). It is a classpath copy for CI; the vault is never read at test time and this directory is not touched
// by scripts/sync-vault-examples.sh (which only mirrors vault-examples/).
import dev.kuml.sysml2.dsl.sysml2Model
import kotlin.time.Duration.Companion.seconds

sysml2Model(name = "Rückluftklappen-Regelung") {
    val start = stateDef(name = "Start", isInitial = true)

    val zRegelbetrieb = stateDef(
        name = "Regelbetrieb",
        entryAction = "Regelung aktiv",
        doAction = "RLK regelt Raumdruck auf Sollwert",
    )
    val zGehalten = stateDef(name = "Gehalten")
    val zKorridorOffen = stateDef(
        name = "Tür Korridor offen",
        parent = zGehalten,
        doAction = "Regelung gesperrt",
    )
    val zMixingOffen = stateDef(
        name = "Tür Mixing offen",
        parent = zGehalten,
        doAction = "Regelung gesperrt",
    )
    val zBeideOffen = stateDef(
        name = "Beide Türen offen",
        parent = zGehalten,
        doAction = "Regelung inaktiv (Störmeldung)",
    )
    val zWartezeit = stateDef(
        name = "Wartezeit",
        parent = zGehalten,
        entryAction = "Verzögerungszeit startet",
        doAction = "Regelung inaktiv (Wartezeit)",
    )
    val zStoerungRlk = stateDef(
        name = "Störung RLK",
        entryAction = "Störmeldung; Klappe verbleibt in Position",
        doAction = "keine Türsperrung",
    )

    transition(name = "init", source = start, target = zRegelbetrieb)
    transition(name = "T10", source = zRegelbetrieb, target = zKorridorOffen, trigger = "Tür Korridor geöffnet", showName = true)
    transition(name = "T11", source = zRegelbetrieb, target = zMixingOffen, trigger = "Tür Mixing geöffnet", showName = true)
    transition(name = "T15", source = zRegelbetrieb, target = zBeideOffen, trigger = "beide Türen gleichzeitig offen", showName = true)
    transition(name = "T12", source = zKorridorOffen, target = zWartezeit, trigger = "Tür Korridor geschlossen", showName = true)
    transition(name = "T13", source = zMixingOffen, target = zWartezeit, trigger = "ANNAHME: Tür Mixing geschlossen", showName = true)
    transition(name = "T16", source = zKorridorOffen, target = zBeideOffen, trigger = "ANNAHME: Tür Mixing geöffnet", showName = true)
    transition(name = "T17", source = zMixingOffen, target = zBeideOffen, trigger = "ANNAHME: Tür Korridor geöffnet", showName = true)
    val t18 = transition(
        name = "T18",
        source = zBeideOffen,
        target = zWartezeit,
        trigger = "ANNAHME: Störung behoben",
        guard = "beide Türen geschlossen",
        showName = true,
    )
    val t19 = transition(name = "T19", source = zWartezeit, target = zKorridorOffen, trigger = "ANNAHME: Tür Korridor erneut geöffnet", showName = true)
    transition(name = "T20", source = zWartezeit, target = zMixingOffen, trigger = "ANNAHME: Tür Mixing erneut geöffnet", showName = true)
    transition(
        name = "T14",
        source = zWartezeit,
        target = zRegelbetrieb,
        timer = after(duration = 10.seconds),
        guard = "beide Türen geschlossen",
        effect = "Regelung aufnehmen",
        showName = true,
    )
    transition(
        name = "T57",
        source = zRegelbetrieb,
        target = zStoerungRlk,
        trigger = "Sollwert nicht erreicht",
        guard = prose(text = "parametrierte Verzögerungszeit nicht spezifiziert"),
        showName = true,
    )
    transition(name = "T58", source = zStoerungRlk, target = zRegelbetrieb, trigger = "ANNAHME: Störung behoben", showName = true)

    comment(
        text = "ANNAHME **A4-E** / OFFEN **A4**: 10 s gelten für beide Türen; Timer startet bei Wiederöffnen neu (T19, T20); fest oder parametriert offen (FUBE 3.2.2, 3.6)",
        t19,
        id = "a4e-51",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A9-E**: T13, T16, T17 aus Tabelle 3.2.2 abgeleitet; T16 gehört zu Tür Korridor offen (FUBE 3.2.2, 3.1.3)",
        zKorridorOffen,
        id = "a9e-51",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "ANNAHME **A9-E**: Rückkehr T18 und T58 sowie Übergang T57 aus Regelbetrieb angenommen; Ausgang der Störungen nicht beschrieben (FUBE 3.1.3, 3.5.2)",
        t18,
        id = "a9e-51-2",
        kind = NoteKind.ASSUMPTION,
    )
    comment(
        text = "OFFEN **A11**: Sollwert nicht erreicht (3.5.1) gegen Endlagenstörung (3.5.2) nicht als identisch belegt; keine Rückmeldung der Klappe (FUBE 2.2, 2.4)",
        zStoerungRlk,
        id = "a11-51",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "OFFEN **A23**: Stellsignal 0-10 V (AUF-ZU) ohne Zuordnung der Endwerte; keine Halteposition bei Verlust von Spannung oder Signal (FUBE 2.2, 3.2.1)",
        zRegelbetrieb,
        id = "a23-51",
        kind = NoteKind.QUESTION,
    )
    comment(
        text = "MODELLHILFE **M1** / **M2** / **M3**: S35, T57, T58 und Start-Zustand sind Workspace-Nummerierung bzw. Hilfskonstrukt; Türnamen gekürzt; nebenläufig zu 50 und 55",
        start,
        id = "m1-51",
        kind = NoteKind.HINT,
    )

    stmDiagram(name = "Zustände der Rückluftklappen-Regelung") {
        include(state = start)
        include(state = zRegelbetrieb)
        include(state = zGehalten)
        include(state = zStoerungRlk)
        layout { direction = Down }
    }
}
