// PAL Welle C2 fixture: three top-level groups, the middle one with a sub group of five cards (local wrapColumns),
// containment / satisfy edges from outside the frames, flow direction Right. Neutral names.
sysml2Model("Nested Wrap") {
    val plant = partDef("Plant")
    val flap = partDef("Flap")

    val top = requirementDef("Top", reqId = "NW-001", text = "The plant shall start on request.", subjectRef = "Plant")
    val low1 = requirementDef("LowOne", reqId = "NW-002", text = "The plant shall report its state.")
    val low2 = requirementDef("LowTwo", reqId = "NW-003", text = "The plant shall log faults.")

    val m1 = requirementDef("MidOne", reqId = "NW-011", text = "The flap shall close within two seconds.")
    val m2 = requirementDef("MidTwo", reqId = "NW-012", text = "The flap shall open within two seconds.")
    val m3 = requirementDef("MidThree", reqId = "NW-013", text = "The flap shall hold its position.")
    val m4 = requirementDef("MidFour", reqId = "NW-014", text = "The flap shall report its position.")
    val m5 = requirementDef("MidFive", reqId = "NW-015", text = "The flap shall resist wind loads.")

    val e1 = requirementDef("EndOne", reqId = "NW-021", text = "The plant shall be maintainable.")
    val e2 = requirementDef("EndTwo", reqId = "NW-022", text = "The plant shall be documented.")

    reqDiagram("Nested wrap") {
        include(plant)
        include(flap)
        group("Overview") {
            include(top)
            include(low1)
            include(low2)
        }
        group("Mechanics") {
            group("Flap behaviour") {
                include(m1)
                include(m2)
                include(m3)
                include(m4)
                include(m5)
                layout { wrapColumns = 3 }
            }
        }
        group("Service") {
            include(e1)
            include(e2)
        }

        satisfy(plant, top)
        satisfy(plant, e1)
        satisfy(flap, m1)
        satisfy(flap, m4)
        contains(top, low1)
        contains(top, low2)
        contains(m1, m2)
        derive(e2, e1)
        layout {
            direction = Right
        }
    }
}
