import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "OrderProcessing") {

    // ── PartDefinitions für `represents`-Targets ────────────────────────
    partDef(name = "Customer")
    partDef(name = "OrderSystem")
    partDef(name = "Warehouse")

    // ── Activity Partitions (Swimlanes) ─────────────────────────────────
    val customerLane = activityPartition(name = "Customer", represents = "Customer")
    val orderSysLane = activityPartition(name = "OrderSystem", represents = "OrderSystem")
    val warehouseLane = activityPartition(name = "Warehouse", represents = "Warehouse")

    // ── Customer Lane ───────────────────────────────────────────────────
    val initial = initialNode(partition = customerLane)
    val placeOrder = actionDef(
        name = "PlaceOrder",
        action = "submit(order)",
        partition = customerLane,
        pins = listOf(ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Output)),
    )

    // ── OrderSystem Lane ────────────────────────────────────────────────
    val validate = actionDef(
        name = "ValidateOrder",
        action = "validate(order)",
        partition = orderSysLane,
        pins = listOf(
            ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Input),
            ActionPin(name = "validation", typeId = "Bool", direction = PinDirection.Output),
        ),
    )
    val decide = decisionNode(name = "valid?", partition = orderSysLane)
    val pay = actionDef(
        name = "ProcessPayment",
        action = "charge(order.total)",
        partition = orderSysLane,
        pins = listOf(ActionPin(name = "validation", typeId = "Bool", direction = PinDirection.Input)),
    )
    val cancel = actionDef(
        name = "CancelOrder",
        action = "notify(order, 'cancelled')",
        partition = orderSysLane,
        pins = listOf(ActionPin(name = "validation", typeId = "Bool", direction = PinDirection.Input)),
    )

    // ── Warehouse Lane ──────────────────────────────────────────────────
    val reserve = actionDef(
        name = "ReserveInventory",
        action = "reserve(order.items)",
        partition = warehouseLane,
        pins = listOf(ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Input)),
    )
    val ship = actionDef(
        name = "ShipOrder",
        action = "dispatch(order)",
        partition = warehouseLane,
        pins = listOf(ActionPin(name = "inventory", typeId = "Inventory", direction = PinDirection.Input)),
    )
    val finalN = finalNode(partition = warehouseLane)
    val flowFinal = flowFinalNode(partition = warehouseLane)

    // ── Control Flows (token-passing edges) ─────────────────────────────
    controlFlow(name = "start", source = initial, target = placeOrder)
    controlFlow(name = "validated", source = validate, target = decide)
    controlFlow(name = "yes", source = decide, target = pay, guard = "valid")
    controlFlow(name = "payToReserve", source = pay, target = reserve)
    controlFlow(name = "reserveToShip", source = reserve, target = ship)
    controlFlow(name = "end", source = ship, target = finalN)
    controlFlow(name = "no", source = decide, target = cancel, guard = "!valid")
    controlFlow(name = "cancelEnd", source = cancel, target = flowFinal)

    // ── Object Flow (token carries a typed object) ──────────────────────
    objectFlow(name = "carryOrder", source = placeOrder, target = validate, objectType = "Order")

    // ── Activity Diagram ────────────────────────────────────────────────
    actDiagram(name = "Order Processing — workflow") {
        include(node = initial)
        include(node = placeOrder)
        include(node = validate)
        include(node = decide)
        include(node = pay)
        include(node = cancel)
        include(node = reserve)
        include(node = ship)
        include(node = finalN)
        include(node = flowFinal)
    }
}
