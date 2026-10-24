import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Tags") {
    applyProfile(ermMappingProfile)

    val order = classOf(name = "Order") {
        stereotype("Entity") { "tableName" to "customer_order"; "kotlinObjectName" to "CustomerOrderTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val tag = classOf(name = "Tag") {
        stereotype("Entity") { "tableName" to "tag"; "kotlinObjectName" to "TagTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "label", type = "String") {
            stereotype("Column") { "columnName" to "label" }
        }
    }

    enumOf(name = "MemberStatus") {
        literal(name = "ACTIVE")
        literal(name = "INACTIVE")
    }

    association(source = order, target = tag, id = "assoc-order-tag") {
        source { multiplicity("0..*") }
        target { multiplicity("0..*") }
    }

}
