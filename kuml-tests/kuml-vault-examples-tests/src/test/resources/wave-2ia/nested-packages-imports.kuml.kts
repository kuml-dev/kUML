// Wave 2I-a fixture: nested packages (4 levels), all three import kinds, a public import, an import of a single member,
// an import that cannot be drawn as an arrow (unknown target and own descendant).
sysml2Model("Platform Structure") {
    pkg("Common") {
        attributeDef("Mass", id = "Common::Mass")
        attributeDef("Voltage", id = "Common::Voltage")
        portDef("PowerPort", id = "Common::PowerPort")
    }
    pkg("Vehicle") {
        imports("Common::*")
        imports("Common::PowerPort", public = true)
        partDef("Vehicle", id = "Vehicle::Vehicle")
        partDef("Chassis", id = "Vehicle::Chassis")
        pkg("Drive") {
            imports("Common::**")
            partDef("Motor", id = "Vehicle::Drive::Motor")
            partDef("Gearbox", id = "Vehicle::Drive::Gearbox")
            pkg("Power") {
                partDef("Battery", id = "Vehicle::Drive::Power::Battery")
                pkg("Cells") {
                    partDef("Cell", id = "Vehicle::Drive::Power::Cells::Cell")
                    partDef("Module", id = "Vehicle::Drive::Power::Cells::Module")
                    pkg("Chemistry") {
                        partDef("Anode", id = "Vehicle::Drive::Power::Cells::Chemistry::Anode")
                    }
                }
            }
        }
    }
    pkg("Safety") {
        imports("Vehicle::Drive::Motor")
        imports("Vehicle::Drive::**")
        imports("Nowhere::*")
        requirementDef("Redundancy", id = "Safety::Redundancy", reqId = "S-1", text = "Drive shall be redundant.")
    }
    packageDiagram("Platform Packages") {
        includeById("pkg:Common")
        includeById("pkg:Vehicle")
        includeById("pkg:Safety")
    }
}
