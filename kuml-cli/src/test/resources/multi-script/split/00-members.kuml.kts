import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Members") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name" }
        }
        attribute(name = "status", type = "MemberStatus") {
            stereotype("Column") { "columnName" to "status"; "enumType" to "demo.MemberStatus" }
        }
    }

    enumOf(name = "MemberStatus") {
        literal(name = "ACTIVE")
        literal(name = "INACTIVE")
    }

}
