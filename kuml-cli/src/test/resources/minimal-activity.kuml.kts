// Minimal activity-diagram fixture.
activityDiagram(name = "Tiny activity") {
    val start = initialNode()
    val a = action(name = "Do thing")
    val end = finalNode()
    edge(from = start, to = a)
    edge(from = a, to = end)
}
