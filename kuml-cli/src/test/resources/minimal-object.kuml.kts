// Minimal object-diagram fixture for RenderPipelineTest.
// Two instances of a single classifier, one link — enough for ELK to lay out.

val customer = UmlClass(
    id = "Customer",
    name = "Customer",
    attributes = listOf(
        UmlProperty(id = "Customer::name", name = "name", type = UmlTypeRef("String")),
    ),
)

objectDiagram(name = "Minimal Object") {
    val alice = instanceOf(classifier = customer, name = "alice") {
        slot(feature = "name", value = literal("\"Alice\""))
    }
    val bob = instanceOf(classifier = customer, name = "bob") {
        slot(feature = "name", value = literal("\"Bob\""))
    }
    link(from = alice, to = bob, targetRole = "friend")
}
