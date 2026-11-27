import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Sensorik der Personalschleuse") {
    // Wertetypen mit Einheit (Einheiten nur, wo die Quelle sie nennt)
    val differenzdruck = attributeDef(name = "Differenzdruck", unitSymbol = "Pa")
    val partikelkonzentration = attributeDef(name = "Partikelkonzentration", unitSymbol = "Partikel/m³")
    val analogsignal = attributeDef(name = "Analogsignal 4-20 mA", unitSymbol = "mA")

    // Aufzählungen
    val tuerzustand = enumerationDef(name = "Türzustand") {
        literal("offen")
        literal("geschlossen")
    }
    val protokoll = enumerationDef(name = "Protokoll") {
        literal("Modbus")
    }

    // Messsignale als Ports mit Flow (Richtung Out: Sensor zu Steuerung)
    val pTuer = portDef(name = "Türzustand digital potentialfrei") {
        flowProperty("türzustand", tuerzustand.id, PortDirection.Out)
    }
    val pDruck = portDef(name = "Differenzdrucksignal") {
        flowProperty("differenzdruck", differenzdruck.id, PortDirection.Out)
    }
    val pPartikel = portDef(name = "Partikelsignal") {
        flowProperty("partikelkonzentration", partikelkonzentration.id, PortDirection.Out)
    }

    // Empfänger der Meldungen (nur als Gegenstelle, Modellhilfe M2)
    val vitec = partDef(name = "Schleusensteuerung Vitec")
    val fmcs = partDef(name = "FMCS")

    val tkKorridor = partDef(name = "Türkontakt Korridor") {
        attribute(name = "messgröße", typeId = tuerzustand.id)
        port(name = "türzustand", typeId = pTuer.id, direction = PortDirection.Out)
        refPart(name = "meldet an", typeId = vitec.id)
    }
    val tkMixing = partDef(name = "Türkontakt Mixing") {
        attribute(name = "messgröße", typeId = tuerzustand.id)
        port(name = "türzustand", typeId = pTuer.id, direction = PortDirection.Out)
        refPart(name = "meldet an", typeId = vitec.id)
    }
    val druck = partDef(name = "Raumdrucksensor") {
        attribute(name = "messgröße", typeId = differenzdruck.id)
        attribute(name = "protokoll", typeId = protokoll.id, defaultLiteral = "Modbus")
        port(name = "differenzdruck", typeId = pDruck.id, direction = PortDirection.Out)
        refPart(name = "meldet an", typeId = fmcs.id)
    }
    val partikel = partDef(name = "Partikelsensor") {
        attribute(name = "messgröße", typeId = partikelkonzentration.id)
        attribute(name = "signal", typeId = analogsignal.id)
        port(name = "partikelkonzentration", typeId = pPartikel.id, direction = PortDirection.Out)
        refPart(name = "meldet an", typeId = fmcs.id)
    }

    comment(text = "OFFEN **A12** (R11): Grenzwert, Zeitkriterium, ausgewählte PAL, Skalierung 4-20 mA und Größenklassen fehlen (FUBE 2.1, 3.3.1)", partikel, id = "a12-31", kind = NoteKind.QUESTION)
    comment(text = "ANNAHME **A21-E**: generische PAL, Partikelsensor nur in ausgewählten PAL (FUBE 2.1, 3.3.1)", partikel, id = "a21e-31", kind = NoteKind.ASSUMPTION)
    comment(text = "ANNAHME **A18-E**: Modell misst die Schleuse gegen den Referenzbereich (FUBE 2.4)", druck, id = "a18e-31", kind = NoteKind.ASSUMPTION)
    comment(text = "OFFEN **A6, A18**: Referenzbereich und Messort des Raumdrucksensors nicht definiert (FUBE 2.4, 3.2.1)", druck, id = "a618-31", kind = NoteKind.QUESTION)
    comment(text = "OFFEN **A19**: Sensor-, Modbus- und Leitungsausfall nicht beschrieben (FUBE 3.5.1)", druck, partikel, id = "a19-31", kind = NoteKind.QUESTION)
    comment(text = "MODELLHILFE **M2**: Aufzählungen, Port-Definitionen und Empfängerblöcke sind Hilfskonstrukte", vitec, id = "m2-31", kind = NoteKind.HINT)

    bdd(name = "Sensorik der Personalschleuse") {
        include(definition = tkKorridor)
        include(definition = tkMixing)
        include(definition = druck)
        include(definition = partikel)
        include(definition = vitec)
        include(definition = fmcs)
        include(definition = tuerzustand)
        include(definition = protokoll)
        include(definition = differenzdruck)
        include(definition = partikelkonzentration)
        include(definition = analogsignal)
        layout {
            direction = Right
        }
    }
}
