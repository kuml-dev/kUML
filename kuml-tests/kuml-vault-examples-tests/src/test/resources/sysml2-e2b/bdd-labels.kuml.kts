// Wave 2E-b fixture: six reference parts of one hub block plus a comment.
sysml2Model("Cooling") {
    val pump = partDef("Pump")
    val valve = partDef("Valve")
    val fan = partDef("Fan")
    val sensor = partDef("Sensor")
    val tank = partDef("Tank")
    val filter = partDef("Filter")
    val fmcs = partDef("FMCS") {
        refPart("pumps", pump.id, dev.kuml.kerml.KermlMultiplicity.ONE_OR_MORE)
        refPart("valve", valve.id)
        refPart("fans", fan.id, dev.kuml.kerml.KermlMultiplicity.ZERO_OR_MORE)
        refPart("sensor", sensor.id, dev.kuml.kerml.KermlMultiplicity.OPTIONAL)
        part("tank", tank.id)
        part("filter", filter.id)
    }
    comment("Hub block: owns all cooling components.", fmcs)
    bdd("Cooling control") {
        include(fmcs)
        include(pump)
        include(valve)
        include(fan)
        include(sensor)
        include(tank)
        include(filter)
    }
}
