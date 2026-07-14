// Activity-diagram fixture identical in structure to minimal-activity-lr.kuml.kts
// but with the (default) TOP_DOWN orientation, used as the comparison baseline
// for the ActivityDiagramConfig.orientation → LayoutHints.direction regression test.
activityDiagram(name = "TD activity") {
    orientation = ActivityOrientation.TOP_DOWN
    val start = initialNode()
    val a = action(name = "Step A")
    val b = action(name = "Step B")
    val end = finalNode()
    edge(from = start, to = a)
    edge(from = a, to = b)
    edge(from = b, to = end)
}
