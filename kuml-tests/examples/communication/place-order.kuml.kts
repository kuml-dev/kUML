// place-order.kuml.kts — UML 2.x communication diagram (V1.1).
// The same scenario as the sequence-diagram place-order example, but laid
// out spatially with numbered messages.

communicationDiagram(name = "Place order — communication view") {
    val ui = role(classifierName = "UI", roleName = "ui")
    val api = role(classifierName = "OrderApi", roleName = "api")
    val db = role(classifierName = "Database", roleName = "db")

    message(from = ui, to = api, label = "POST /orders")
    message(from = api, to = db, label = "INSERT")
    message(from = db, to = api, label = "id")
    message(from = api, to = ui, label = "201 Created")
}
