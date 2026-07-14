// Activity-diagram fixture with an explicit LEFT_RIGHT orientation hint.
// Regression fixture for the ActivityDiagramConfig.orientation → LayoutHints.direction
// wiring: previously this setting had zero effect on the rendered layout.
activityDiagram(name = "LR activity") {
    orientation = ActivityOrientation.LEFT_RIGHT
    val start = initialNode()
    val a = action(name = "Step A")
    val b = action(name = "Step B")
    val end = finalNode()
    edge(from = start, to = a)
    edge(from = a, to = b)
    edge(from = b, to = end)
}
