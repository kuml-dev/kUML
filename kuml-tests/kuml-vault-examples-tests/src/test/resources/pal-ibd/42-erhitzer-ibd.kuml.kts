import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.units.degC

sysml2Model(name = "Innenstruktur E-Erhitzer-Regelung") {
    val T = "E-Erhitzer-Regelung"
    val F = "fmcs"

    // Verbindungsarten (Modellierungshilfe M2)
    val leitungAnalog = connectionDef(name = "Analog")
    val leitungSignal = connectionDef(name = "Signal")
    val leitungVersorgung = connectionDef(name = "Leistung")

    // Attributtypen: Temperatur mit Einheit, Reglertyp als Aufzählung
    val temperatur = attributeDef(name = "Temperatur", unitSymbol = "°C")
    val reglertyp = enumerationDef(name = "Reglertyp") {
        literal("PI-Regler")
    }

    // Port-Typen (kurze Namen, damit Port-Labels nicht abgeschnitten werden)
    val portAnalog = portDef(name = "Analog")
    val portSignal = portDef(name = "Signal")
    val portLeistung = portDef(name = "Leistung")

    val fmcs = partDef(name = "FMCS") {
        attribute(name = "reglertyp", typeId = reglertyp.id, defaultLiteral = "PI-Regler")
        port(name = "zuluft", typeId = portSignal.id, direction = PortDirection.In)
        port(name = "raum", typeId = portSignal.id, direction = PortDirection.In)
        port(name = "stell", typeId = portAnalog.id, direction = PortDirection.Out)
    }
    val erhitzer = partDef(name = "E-Erhitzer") {
        attribute(name = "sollwertRaumtemperatur", typeId = temperatur.id, default = 20.degC)
        port(name = "leistung", typeId = portLeistung.id, direction = PortDirection.In)
        port(name = "stell", typeId = portAnalog.id, direction = PortDirection.In)
    }
    val raumsensor = partDef(name = "Raumtemperatursensor") {
        port(name = "raum", typeId = portSignal.id, direction = PortDirection.Out)
    }
    val kanalsensor = partDef(name = "Kanaltemperatursensor") {
        port(name = "zuluft", typeId = portSignal.id, direction = PortDirection.Out)
    }
    val kanal = partDef(name = "Zuluftkanal")
    val versorgung = partDef(name = "Spannungsversorgung E-Erhitzer") {
        port(name = "leistung", typeId = portLeistung.id, direction = PortDirection.Out)
    }
    val auftraggeber = partDef(name = "Auftraggeber")

    val teilsystem = partDef(name = T) {
        part(name = "versorgung", typeId = versorgung.id)
        part(name = "kanalsensor", typeId = kanalsensor.id)
        part(name = "raumsensor", typeId = raumsensor.id)
        part(name = F, typeId = fmcs.id)
        part(name = "erhitzer", typeId = erhitzer.id)
        part(name = "zuluftkanal", typeId = kanal.id)
        part(name = "auftraggeber", typeId = auftraggeber.id)

        // I13 (3.7.3)
        connect(name = "I13", typeId = leitungAnalog.id, sourceEndId = "$T::$F::stell", targetEndId = "$T::erhitzer::stell")
        flow(item = "Stellsignal 0-10 V", from = "$T::$F::stell", to = "$T::erhitzer::stell", via = "I13")
        // I14 (3.7.1, 3.7.3)
        connect(name = "I14", typeId = leitungSignal.id, sourceEndId = "$T::raumsensor::raum", targetEndId = "$T::$F::raum")
        flow(item = "Raumtemperatur", from = "$T::raumsensor::raum", to = "$T::$F::raum", via = "I14")
        // I15 (3.7.1), Empfaenger und Richtung abgeleitet
        connect(name = "I15", typeId = leitungSignal.id, sourceEndId = "$T::kanalsensor::zuluft", targetEndId = "$T::$F::zuluft")
        flow(item = "Zulufttemperatur", from = "$T::kanalsensor::zuluft", to = "$T::$F::zuluft", via = "I15")
        // I20 (3.7.4)
        connect(name = "I20", typeId = leitungVersorgung.id, sourceEndId = "$T::versorgung::leistung", targetEndId = "$T::erhitzer::leistung")
        flow(item = "Versorgung", from = "$T::versorgung::leistung", to = "$T::erhitzer::leistung", via = "I20")
    }

    comment(
        text = "ANNAHME **A13-E**: Empfänger des Kanaltemperatursensors ist das FMCS, Richtung I15 abgeleitet (FUBE 3.7.1)",
        "$T::I15",
        id = "a13e-42",
        kind = NoteKind.ASSUMPTION,
        placement = NotePlacement.ABOVE,
    )
    comment(
        text = "OFFEN **A19**: Ausfall von Raum- und Kanaltemperatursensor nicht als Störfall beschrieben (FUBE 3.5.1)",
        "$T::kanalsensor",
        id = "a19-42",
        kind = NoteKind.QUESTION,
        placement = NotePlacement.ABOVE,
    )
    comment(
        text = "OFFEN **A20**: MAL ungenutzt; GLT, STB, E-Erhitzer fehlen im Abkürzungsverzeichnis (FUBE 1.3)",
        "$T::erhitzer",
        id = "a20-42",
        kind = NoteKind.QUESTION,
        placement = NotePlacement.BELOW,
    )
    comment(
        text = "MODELLHILFE **M2**: Zuluftkanal (C23) ist abgeleiteter Name, die Quelle sagt nur Kanal (FUBE 3.7.1, 3.7.2); Teilsystem ohne Quellbeleg",
        "$T::zuluftkanal",
        id = "m2-42",
        kind = NoteKind.HINT,
        placement = NotePlacement.BELOW,
    )

    ibd(name = "Innenstruktur E-Erhitzer-Regelung", owner = teilsystem) {
        layout {
            direction = Down
            last("$T::zuluftkanal")
            last("$T::auftraggeber")
        }
    }
}
