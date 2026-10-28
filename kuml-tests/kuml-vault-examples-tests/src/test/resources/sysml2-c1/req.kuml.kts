sysml2Model("Vehicle Requirements") {
    val car = partDef("Car")
    val brakes = partDef("BrakeSystem")
    val top = requirementDef(
        "TopSpeed",
        reqId = "R-001",
        text = "The vehicle shall reach at least 180 km/h on flat road within 30 seconds.",
        priority = RequirementPriority.HIGH,
        status = RequirementStatus.APPROVED,
        source = "Marketing, Product Brief 2026",
        subjectRef = "Car",
    )
    val stop = requirementDef(
        "StoppingDistanceFromHundredKilometresPerHourOnDryAndWetRoadSurfaces",
        reqId = "R-002",
        text = "The vehicle shall stop from 100 km/h within 36 metres on a dry road.",
        priority = RequirementPriority.MEDIUM,
        status = RequirementStatus.DRAFT,
    )
    val stopCopy = requirementDef(
        "StoppingCopy",
        text = "The vehicle shall stop from 100 km/h within 36 metres on a dry road.",
    )
    val dry = requirementDef(
        "DryRoadAssumption",
        text = "Tests are run on dry asphalt at 20 degrees Celsius.",
        isAssumption = true,
    )
    val abs = requirementDef("AbsRequired", text = "ABS is mandatory.", priority = RequirementPriority.LOW)
    val speedTest = testCaseDef("TopSpeedTest", text = "Accelerate on the test track.")
    val driving = useCaseDef("DriveFast")
    reqDiagram("Vehicle requirements") {
        include(car)
        include(brakes)
        include(driving)
        include(top)
        include(speedTest)
        group("Performance") {
            include(top)
            group("Braking") {
                include(stop)
                group("Details") {
                    include(stopCopy)
                    include(abs)
                }
            }
        }
        include(dry)
        satisfy(car, top)
        satisfy(brakes, stop)
        verify(speedTest, top)
        derive(abs, stop)
        contains(stop, abs)
        refine(driving, top)
        trace(dry, stop)
        copy(stopCopy, stop)
    }
}
