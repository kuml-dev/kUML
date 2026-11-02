// Wave 2I-a fixture: views and viewpoints, a view without a drawn viewpoint, a standalone definition card.
sysml2Model("Views") {
    pkg("Vehicle") {
        partDef("Vehicle", id = "Vehicle::Vehicle")
        partDef("Engine", id = "Vehicle::Engine")
    }
    val safety =
        viewpoint("Safety") {
            stakeholder("Safety engineer")
            stakeholder("Certification authority")
            concern("Hazards are covered")
            concern("Failure rates are bounded")
        }
    val cost =
        viewpoint("Cost") {
            stakeholder("Product owner")
        }
    val overview =
        view("Safety Overview") {
            satisfies(safety)
            expose("Vehicle::*")
            filter("@Safety")
        }
    val bom =
        view("Bill of Materials") {
            satisfies("Cost")
            expose("Vehicle::**")
        }
    view("Orphan View") {
        satisfies("Unknown")
        expose("Everything")
    }
    packageDiagram("Views and Viewpoints") {
        include(overview)
        include(bom)
        include(safety)
        include(cost)
        includeById("pkg:Vehicle")
    }
}
