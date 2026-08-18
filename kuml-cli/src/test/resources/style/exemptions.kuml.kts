// style/exemptions.kuml.kts — exercises two style-check exemptions at once:
// a single-value-parameter call (`extends`, positional and still exempt) and
// a block-DSL trailing lambda (`classOf(...) { ... }`, exempt regardless of
// how the lambda body is invoked). Must pass the style check cleanly.
diagram(name = "Exemptions", type = DiagramType.CLASS) {
    val animal = classOf(name = "Animal")
    classOf(name = "Dog") {
        extends(animal)
    }
}
