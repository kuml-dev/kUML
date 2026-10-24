import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Shop") {
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

    val order = classOf(name = "Order") {
        stereotype("Entity") { "tableName" to "customer_order"; "kotlinObjectName" to "CustomerOrderTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "total", type = "Int") {
            stereotype("Column") { "columnName" to "total" }
        }
        attribute(name = "status", type = "OrderStatus") {
            stereotype("Column") { "columnName" to "status"; "enumType" to "demo.OrderStatus" }
        }
    }

    enumOf(name = "OrderStatus") {
        literal(name = "OPEN")
        literal(name = "SHIPPED")
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

    association(source = order, target = member, id = "assoc-order-member") {
        source { multiplicity("0..*") }
        target { multiplicity("1"); role = "memberId" }
    }

    association(source = order, target = tag, id = "assoc-order-tag") {
        source { multiplicity("0..*") }
        target { multiplicity("0..*") }
    }

}
