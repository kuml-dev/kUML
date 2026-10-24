import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Orders") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
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

    association(source = order, target = member, id = "assoc-order-member") {
        source { multiplicity("0..*") }
        target { multiplicity("1"); role = "memberId" }
    }

}
