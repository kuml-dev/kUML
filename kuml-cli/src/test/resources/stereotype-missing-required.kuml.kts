// Fixture: JavaEE Entity without required 'tableName'.
// Bypasses the DSL build-time check by constructing KumlStereotypeApplication directly.
// Used to test kuml validate --check-stereotypes → exit 4.
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.uml.UmlClass

diagram(name = "Missing Required Tag", type = DiagramType.CLASS) {
    // Manually add a class element with a stereotype application that is missing 'tableName'
    addNamedElement(
        UmlClass(
            id = "User",
            name = "User",
            appliedStereotypes = listOf(
                KumlStereotypeApplication(
                    profileNamespace = "dev.kuml.profiles.javaee",
                    stereotypeName = "Entity",
                    tags = emptyMap(), // missing required 'tableName'
                )
            )
        )
    )
}
