@file:Suppress("unused")

/** UML Activity fixture with a Decision node (ADR-0015 CLI test). */
activityDiagram(name = "Decision Activity") {
    val start = initialNode()
    val dec = decision()
    val yes = action(name = "yes")
    val no = action(name = "no")
    val end = finalNode()

    edge(from = start, to = dec)
    edge(from = dec, to = yes, guard = "go")
    edge(from = dec, to = no, guard = "!go")
    edge(from = yes, to = end)
    edge(from = no, to = end)
}
