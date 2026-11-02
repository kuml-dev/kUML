// Wave 2I-b fixture: an analysis case with a subject and two calculations, one of them with a long expression.
sysml2Model("Mass analysis") {
    val mass = attributeDef("Mass", unitSymbol = "kg")
    val vehicle = partDef("Vehicle") {
        attribute("curbMass", mass.id)
    }
    val totalMass = calcDef("TotalMass") {
        input("chassis", "Mass")
        input("battery", "Mass")
        input("payload", "Mass")
        expression("chassis + battery + payload + sum(accessories) * safetyFactor + tolerance")
        returns("total", "Mass")
    }
    val margin = calcDef("PayloadMargin") {
        input("limit", "Mass")
        input("total", "Mass")
        output("margin", "Mass")
        expression("limit - total")
        returns("margin", "Mass")
    }
    val analysis = analysisDef("MassAnalysis") {
        subject(vehicle)
        objective("Check that the loaded vehicle stays below the permitted gross mass.")
        calc("total", totalMass)
        calc("margin", margin)
        returns("verdict", "Boolean")
    }
    bdd("Mass analysis") {
        include(analysis)
        include(totalMass)
        include(margin)
        include(vehicle)
        include(mass)
    }
}
