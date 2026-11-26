import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "Druckregelung und Einfrieren") {
    // Typen der Signale und Werte (Einheit im Namen, weil kUML unitSymbol im Attribut nicht zeigt)
    val tDruck = attributeDef(name = "Differenzdruck in Pa", unitSymbol = "Pa")
    val tRaumdruck = attributeDef(name = "Raumdruck in Pa", unitSymbol = "Pa")
    val tStell = attributeDef(name = "Stellsignal in V", unitSymbol = "V")
    val tEinfrieren = attributeDef(name = "Einfrierbefehl")

    // Portdefinitionen mit Signalrichtung (Protokolle laut FUBE 2.2 und 2.4)
    val portModbus = portDef(name = "Modbus") {
        flowProperty(name = "differenzdruck", typeId = tDruck.id, direction = PortDirection.Out)
    }
    val portAnalog = portDef(name = "0-10 V") {
        flowProperty(name = "stellsignal", typeId = tStell.id, direction = PortDirection.Out)
    }
    val portOffen = portDef(name = "offen") {
        flowProperty(name = "einfrierbefehl", typeId = tEinfrieren.id, direction = PortDirection.Out)
    }
    // Modellierungshilfe: Bezug ist kein Signal
    val portBezug = portDef(name = "Bezug")

    // Verbindungsdefinitionen: Signalleitung farbig (rot belegt, ocker Annahme), Bezug gestrichelt
    val verbModbus = connectionDef(name = "Modbus", color = "#C62828")
    val verbAnalog = connectionDef(name = "Analog 0-10 V", color = "#C62828")
    val verbOffen = connectionDef(name = "offen", color = "#A85F00")
    val verbBezug = connectionDef(name = "Bezug", style = dev.kuml.sysml2.ConnectionLineStyle.DASHED)

    val raumdrucksensor = partDef(name = "Raumdrucksensor") {
        attribute(name = "messgröße", typeId = tDruck.id)
        port(name = "druck", typeId = portModbus.id, direction = PortDirection.Out)
        port(name = "messung referenz", typeId = portBezug.id)
        port(name = "messung schleuse", typeId = portBezug.id)
    }
    val rlk = partDef(name = "Rückluftklappe") {
        // Attributname bewusst verschieden vom Port stellsignal (sonst Port ohne Richtungsdreieck)
        attribute(name = "stellwert", typeId = tStell.id)
        port(name = "stellsignal", typeId = portAnalog.id, direction = PortDirection.In)
        port(name = "stellwirkung", typeId = portBezug.id)
    }
    val vsr = partDef(name = "Volumenstromregler") {
        port(name = "einfrieren", typeId = portOffen.id, direction = PortDirection.In)
    }
    val klappenantrieb = partDef(name = "Klappenantrieb") {
        port(name = "einfrieren", typeId = portOffen.id, direction = PortDirection.In)
    }
    val fmcs = partDef(name = "FMCS") {
        port(name = "druck", typeId = portModbus.id, direction = PortDirection.In)
        port(name = "stellsignal", typeId = portAnalog.id, direction = PortDirection.Out)
        port(name = "einfrieren", typeId = portOffen.id, direction = PortDirection.Out)
    }
    val schleusenraum = partDef(name = "Schleusenraum") {
        // P2: PAL 0 bis +5 Pa gegenüber Korridor (kein Wertebereich in kUML: zwei Attribute)
        attribute(name = "raumdruck von ggü. Korridor", typeId = tRaumdruck.id, default = 0.0)
        attribute(name = "raumdruck bis ggü. Korridor", typeId = tRaumdruck.id, default = 5.0)
        port(name = "messpunkt", typeId = portBezug.id)
        port(name = "stelleingriff", typeId = portBezug.id)
    }
    val referenzbereich = partDef(name = "Referenzbereich") {
        port(name = "messpunkt", typeId = portBezug.id)
    }

    val ts = "Teilsystem Druckregelung"
    val druckregelung = partDef(name = ts) {
        part(name = "fmcs", typeId = fmcs.id)
        part(name = "raumdrucksensor", typeId = raumdrucksensor.id)
        part(name = "referenzbereich", typeId = referenzbereich.id)
        part(name = "schleusenraum", typeId = schleusenraum.id)
        part(name = "rlk", typeId = rlk.id)
        part(name = "vsr", typeId = vsr.id)
        part(name = "antrieb", typeId = klappenantrieb.id)

        // I6: Raumdrucksensor an FMCS per Modbus (belegt, 2.4)
        connect(name = "I6", typeId = verbModbus.id, sourceEndId = "$ts::raumdrucksensor::druck", targetEndId = "$ts::fmcs::druck")
        flow(item = "Differenzdruck", from = "$ts::raumdrucksensor::druck", to = "$ts::fmcs::druck", via = "I6")

        // I8: FMCS an Rueckluftklappe, Analog 0-10 V (Richtung aus 3.2.1 abgeleitet); Item-Flow ohne via (Labelkollision P-IBD-04)
        connect(name = "I8", typeId = verbAnalog.id, sourceEndId = "$ts::fmcs::stellsignal", targetEndId = "$ts::rlk::stellsignal")
        flow(item = "Stellsignal", from = "$ts::fmcs::stellsignal", to = "$ts::rlk::stellsignal")

        // I11: Einfrierbefehl an VSR und Klappenantrieb (ANNAHME A5-E: Ausfuehrender FMCS); kurzer Name gegen Labelkollision
        connect(name = "I11 VSR", typeId = verbOffen.id, sourceEndId = "$ts::fmcs::einfrieren", targetEndId = "$ts::vsr::einfrieren")
        flow(item = "Einfrierbefehl", from = "$ts::fmcs::einfrieren", to = "$ts::vsr::einfrieren", via = "I11 VSR")
        connect(name = "I11 Ant", typeId = verbOffen.id, sourceEndId = "$ts::fmcs::einfrieren", targetEndId = "$ts::antrieb::einfrieren")
        flow(item = "Einfrierbefehl", from = "$ts::fmcs::einfrieren", to = "$ts::antrieb::einfrieren", via = "I11 Ant")

        // Bezugsverbindungen (Modellierungshilfe A18-E, kein Signal, ungerichtet)
        connect(name = "Messbezug Schleuse", typeId = verbBezug.id, sourceEndId = "$ts::raumdrucksensor::messung schleuse", targetEndId = "$ts::schleusenraum::messpunkt")
        connect(name = "Messbezug Referenz", typeId = verbBezug.id, sourceEndId = "$ts::raumdrucksensor::messung referenz", targetEndId = "$ts::referenzbereich::messpunkt")
        connect(name = "Stellwirkung", typeId = verbBezug.id, sourceEndId = "$ts::rlk::stellwirkung", targetEndId = "$ts::schleusenraum::stelleingriff")
    }

    comment(
        text = "OFFEN **A5**: VSR in FUBE 2.1, nicht in 2.2 und 2.4; Klappenantrieb in keiner Tabelle (FUBE 2.1, 3.6)",
        "$ts::vsr",
        "$ts::antrieb",
        id = "a5-41",
        kind = NoteKind.QUESTION,
        placement = NotePlacement.BELOW,
    )
    comment(
        text = "ANNAHME **A5-E**: FMCS friert ein und gibt frei (I11); Ausführender und Protokoll nicht genannt (FUBE 3.6)",
        "FMCS::einfrieren",
        id = "a5e-41",
        kind = NoteKind.ASSUMPTION,
        placement = NotePlacement.LEFT,
    )
    comment(
        text = "OFFEN **A19** / **A23** / **A11**: Endwerte AUF-ZU, Halteposition, Rückmeldung und Ausfall von Sensor, Modbus oder FMCS nicht beschrieben (FUBE 2.2, 2.4, 3.5)",
        "$ts::rlk",
        id = "a23-41",
        kind = NoteKind.QUESTION,
        placement = NotePlacement.BELOW,
    )
    comment(
        text = "ANNAHME **A6-E** / **A18-E**: Schwellen gleichgesetzt; Sensor misst Schleuse gegen Referenzbereich (FUBE 2.4, 3.2.1, 3.4)",
        "$ts::raumdrucksensor",
        id = "a6e-41",
        kind = NoteKind.ASSUMPTION,
        placement = NotePlacement.ABOVE,
    )
    comment(
        text = "OFFEN **A6** / **A18** / **P15**: Referenzbereich, Messort und Sollwert nicht definiert beziehungsweise nicht beziffert (FUBE 2.4, 3.2.1)",
        "$ts::referenzbereich",
        id = "a6-41",
        kind = NoteKind.QUESTION,
        placement = NotePlacement.ABOVE,
    )
    comment(
        text = "HINWEIS **P1** / **P2**: Mixing -5 Pa, PAL 0 bis +5 Pa gegenüber Korridor (FUBE 2.1, 3.2.1)",
        "$ts::schleusenraum",
        id = "p1-41",
        kind = NoteKind.HINT,
        placement = NotePlacement.RIGHT,
    )
    comment(
        text = "MODELLHILFE **M2**: Teilsystem, Portdefinition Bezug und ungerichtete Bezugslinien sind Hilfskonstrukte (A18-E)",
        "$ts::referenzbereich",
        id = "m2-41",
        kind = NoteKind.HINT,
        placement = NotePlacement.ABOVE,
    )

    ibd(name = "Innenstruktur Druckregelung und Einfrieren", owner = druckregelung) {
        layout {
            direction = Down
            // Sensor oben, FMCS in der Mitte, Aktoren in einer Ebene unten (Regelkreis ist zyklisch)
            first("$ts::raumdrucksensor")
            last("$ts::vsr", "$ts::antrieb", "$ts::rlk")
        }
    }
}
