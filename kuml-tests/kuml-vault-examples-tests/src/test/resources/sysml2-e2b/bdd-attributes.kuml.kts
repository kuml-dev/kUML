// Wave 2E-b fixture: attribute defaults with units, an enumeration, a reference part, an action definition.
sysml2Model("Room") {
    val pressure = attributeDef("Raumdruck", unitSymbol = "Pa")
    val colour = enumerationDef("Ampelfarbe") {
        literal("Rot")
        literal("Gelb")
    }
    val temp = partDef("Sensor")
    val room = partDef("Raum") {
        attribute("raumdruck", pressure.id, default = -5.0)
        attribute("farbe", colour.id, defaultLiteral = "Rot")
        refPart("sensor", temp.id)
    }
    val act = actionDef("Lueften")
    bdd("Room attributes") {
        include(pressure)
        include(colour)
        include(room)
        include(temp)
        include(act)
    }
}
