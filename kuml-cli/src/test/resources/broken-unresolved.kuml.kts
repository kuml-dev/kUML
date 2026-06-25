// broken-unresolved.kuml.kts — fixture with a compile error for `kuml diagnostics`.
diagram(name = "Broken", type = DiagramType.CLASS) {
    classOf("A")
    nonsenseFunction(foo = bar)
}
