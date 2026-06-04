// Fixture: JavaEE Entity with all required tags provided.
// No stereotype violations expected.
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.toTagValue
import dev.kuml.uml.UmlClass

diagram(name = "Clean Stereotype", type = DiagramType.CLASS) {
    addNamedElement(
        UmlClass(
            id = "Product",
            name = "Product",
            appliedStereotypes = listOf(
                KumlStereotypeApplication(
                    profileNamespace = "dev.kuml.profiles.javaee",
                    stereotypeName = "Entity",
                    tags = mapOf("tableName" to "products".toTagValue()),
                )
            )
        )
    )
}
