import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "A") {
    applyProfile(ermMappingProfile)

    classOf(name = "Address") {
        stereotype("Entity") { "tableName" to "addresses" }
        attribute(name = "street", type = "String")
        attribute(name = "city", type = "String")
    }
}
