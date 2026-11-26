import dev.kuml.sysml2.ConnectionLineStyle
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.units.degC

sysml2Model(name = "Innenstruktur E-Erhitzer STB") {
    val T = "Teilsystem E-Erhitzer-STB"
    val F = "fmcs"

    val leitungSignal = connectionDef(name = "Signal")
    val leitungWirkpfad = connectionDef(name = "Wirkpfad", style = ConnectionLineStyle.DASHED)
    val temperatur = attributeDef(name = "Temperatur", unitSymbol = "°C")
    val portSignal = portDef(name = "Signal")

    val fmcs = partDef(name = "FMCS") {
        port(name = "stb", typeId = portSignal.id, direction = PortDirection.In)
    }
    val erhitzer = partDef(name = "E-Erhitzer") {
        port(name = "stb", typeId = portSignal.id, direction = PortDirection.In)
    }
    val stb = partDef(name = "Sicherheitstemperaturbegrenzer (STB)") {
        attribute(name = "maxKanaltemperatur", typeId = temperatur.id, default = 70.degC)
        port(name = "stbE", typeId = portSignal.id, direction = PortDirection.Out)
        port(name = "stbF", typeId = portSignal.id, direction = PortDirection.Out)
    }

    val teilsystem = partDef(name = T) {
        part(name = "stb", typeId = stb.id)
        part(name = F, typeId = fmcs.id)
        part(name = "erhitzer", typeId = erhitzer.id)

        connect(name = "I17 E", typeId = leitungSignal.id, sourceEndId = "$T::stb::stbE", targetEndId = "$T::erhitzer::stb")
        flow(item = "STB-Status", from = "$T::stb::stbE", to = "$T::erhitzer::stb", via = "I17 E")
        connect(name = "I17 F", typeId = leitungWirkpfad.id, sourceEndId = "$T::stb::stbF", targetEndId = "$T::$F::stb")
        flow(item = "STB-Status", from = "$T::stb::stbF", to = "$T::$F::stb", via = "I17 F")
    }

    comment(
        text = "ANNAHME **A13-E**: Strömungswächter und STB wirken auf E-Erhitzer und FMCS, beide Lesarten gezeichnet; Richtung abgeleitet (FUBE 3.7.2, 3.7.3)",
        "$T::I17 F",
        id = "a13e-47",
        kind = NoteKind.ASSUMPTION,
        placement = NotePlacement.BELOW,
    )
    comment(
        text = "ANNAHME **A3-E**: STB manuell zurückgesetzt, Akteur nicht genannt (FUBE 3.7.2)",
        "$T::stb",
        id = "a3e-46",
        kind = NoteKind.ASSUMPTION,
        placement = NotePlacement.ABOVE,
    )

    ibd(name = "Innenstruktur E-Erhitzer STB", owner = teilsystem) {
        layout { direction = Down }
    }
}
