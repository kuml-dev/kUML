// order-service.kuml.kts — UML 2.x composite-structure diagram (V1.1).
// Internal structure of an OrderService component: two provided interfaces,
// one required interface, wired together by ports.

compositeStructureDiagram(name = "OrderService — internal structure") {
    val placeOrder = interfaceOf(name = "IPlaceOrder") {
        operation(name = "placeOrder")
    }
    val ordersDb = interfaceOf(name = "IOrdersDb") {
        operation(name = "save")
    }

    val orderService = component(name = "OrderService") {
        port(name = "api")
        port(name = "db")
        provides(iface = placeOrder)
        requires(iface = ordersDb)
    }

    val dbAdapter = component(name = "PostgresAdapter") {
        port(name = "out")
        provides(iface = ordersDb)
    }

    connect(end1 = orderService, port1 = "db", end2 = dbAdapter, port2 = "out")
}
