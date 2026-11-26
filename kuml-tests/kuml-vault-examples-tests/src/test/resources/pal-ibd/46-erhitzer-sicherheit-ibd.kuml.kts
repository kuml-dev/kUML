import dev.kuml.sysml2.ConnectionLineStyle
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Innenstruktur E-Erhitzer Strömungswächter") {
    val T = "Teilsystem E-Erhitzer-Strömungswächter"
    val F = "fmcs"

    val leitungSignal = connectionDef(name = "Signal")
    val leitungWirkpfad = connectionDef(name = "Wirkpfad", style = ConnectionLineStyle.DASHED)
    val portSignal = portDef(name = "Signal")

    val fmcs = partDef(name = "FMCS") {
        port(name = "stroemung", typeId = portSignal.id, direction = PortDirection.In)
    }
    val erhitzer = partDef(name = "E-Erhitzer") {
        port(name = "stroemung", typeId = portSignal.id, direction = PortDirection.In)
    }
    val stroemungswaechter = partDef(name = "Strömungswächter") {
        port(name = "stroemungE", typeId = portSignal.id, direction = PortDirection.Out)
        port(name = "stroemungF", typeId = portSignal.id, direction = PortDirection.Out)
    }

    val teilsystem = partDef(name = T) {
        part(name = "stroemungswaechter", typeId = stroemungswaechter.id)
        part(name = F, typeId = fmcs.id)
        part(name = "erhitzer", typeId = erhitzer.id)

        connect(name = "I16 E", typeId = leitungSignal.id, sourceEndId = "$T::stroemungswaechter::stroemungE", targetEndId = "$T::erhitzer::stroemung")
        flow(item = "Strömung", from = "$T::stroemungswaechter::stroemungE", to = "$T::erhitzer::stroemung", via = "I16 E")
        connect(name = "I16 F", typeId = leitungWirkpfad.id, sourceEndId = "$T::stroemungswaechter::stroemungF", targetEndId = "$T::$F::stroemung")
        flow(item = "Strömung", from = "$T::stroemungswaechter::stroemungF", to = "$T::$F::stroemung", via = "I16 F")
    }

    comment(
        text = "ANNAHME **A13-E**: Strömungswächter wirkt auf E-Erhitzer und FMCS, beide Lesarten gezeichnet; Richtung abgeleitet (FUBE 3.7.2, 3.7.3)",
        "$T::I16 F",
        id = "a13e-46",
        kind = NoteKind.ASSUMPTION,
        placement = NotePlacement.RIGHT,
    )
    comment(
        text = "OFFEN **A13**: Wiederanlauf und Signaltypen offen (FUBE 3.7.2)",
        "$T::I16 E",
        id = "a13-46",
        kind = NoteKind.QUESTION,
        placement = NotePlacement.LEFT,
    )

    ibd(name = "Innenstruktur E-Erhitzer Strömungswächter", owner = teilsystem) {
        layout { direction = Down }
    }
}
