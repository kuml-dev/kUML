// style/named.kuml.kts — same model as positional.kuml.kts, but with the
// dev.kuml.* argument named. Must pass the style check cleanly.
diagram(name = "Named", type = DiagramType.CLASS) {
    classOf(name = "Widget")
}
