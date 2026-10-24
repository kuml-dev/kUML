import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "M") {
    val engineDef = partDef(name = "Engine")
    val vehicle =
        partDef(name = "Vehicle") {
            part(name = "engine", typeId = engineDef.id)
        }
    ibd(name = "Vehicle wiring", owner = vehicle)
}
