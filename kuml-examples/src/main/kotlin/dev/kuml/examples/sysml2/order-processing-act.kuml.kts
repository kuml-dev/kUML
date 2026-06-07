@file:Suppress("unused")

import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Order Processing — SysML 2 Activity Diagram example (V2.0.10 baseline +
 * V2.0.16 Partitions/Pins polish).
 *
 * Illustriert die V2.0.10-Oberfläche end-to-end mit allen sieben
 * Aktivitäts-Knoten-Kinds und beiden Edge-Kinds, **erweitert um die
 * V2.0.16-Polish-Welle**:
 *  - **Three Activity Partitions** (Swimlanes) — `Customer`, `OrderSystem`,
 *    `Warehouse` — gruppieren die Action-Knoten nach ausführender Entität.
 *  - **Typed pins** auf den Aktionen — `PlaceOrder.orderDetails (Output)`,
 *    `ValidateOrder.orderDetails (Input)` / `validation (Output)`,
 *    `ProcessPayment.validation (Input)`, `CancelOrder.validation (Input)`,
 *    `ReserveInventory.orderDetails (Input)`, `ShipOrder.inventory (Input)`.
 *
 * Activity nodes pro Partition:
 *  - **Customer-Lane**: `Initial` (Start), `PlaceOrder` (Output-Pin
 *    `orderDetails`).
 *  - **OrderSystem-Lane**: `ValidateOrder` (Input/Output Pins), `valid?`
 *    Decision, `ProcessPayment`, `CancelOrder`.
 *  - **Warehouse-Lane**: `ReserveInventory`, `ShipOrder`, `Final`, `FlowFinal`.
 *
 * Edges:
 *  - **Control Flows** zwischen allen Knoten — die token-tragenden Edges.
 *  - **Ein Object Flow** vom `PlaceOrder.orderDetails`-Output zum
 *    `ValidateOrder.orderDetails`-Input, der den `Order`-Datensatz
 *    transportiert (`objectType = "Order"`). Endpunkt-Anchoring an
 *    spezifischen Pins ist V2.x — der Flow landet aktuell am Knoten-
 *    Mittelpunkt.
 *
 * Token-Fluss-Domäne: ein Order-Processing-Workflow validiert eine Bestellung,
 * verzweigt bei Ungültigkeit in Cancellation, sonst splittet er in
 * parallele Zahlung + Inventar-Reservierung, synchronisiert die Branches und
 * versendet schließlich die Bestellung.
 *
 * Flows leben wie bei STM-Transitionen auf dem Modell (`controlFlow` /
 * `objectFlow` registrieren in `Sysml2Model.usages`), nicht auf dem
 * Diagramm — die zukünftige Behaviour-Runtime-Welle braucht sie zur
 * Laufzeit. Der Bridge zieht sie automatisch in das ACT-Diagramm, sobald
 * beide Endpunkte sichtbar sind. **V2.0.16: Partitions folgen demselben
 * Pattern — sie werden über die `partition = …`-Referenz auf den
 * Action-Knoten vom Bridge automatisch als `LayoutGroup` materialisiert.**
 *
 * Out of V2.0.16 scope (siehe Wave-Plan):
 *  - Horizontal Partitions (Lanes laufen nach rechts statt nach unten)
 *  - Hierarchische / nested Partitions
 *  - Interruptible regions
 *  - Stream-Flow / Multicast-Semantics auf Object Flow
 *  - `[guard]` / `[ObjectType]` Edge-Labels in SVG und TikZ (gleiche
 *    Limitation wie UC / REQ / STM)
 *  - ObjectFlow-Endpunkt-Anchoring an spezifischen Pins
 *  - LaTeX-Rendering von Partitions + Pins (SVG-only in V2.0.16)
 *  - Live Token-Flow-Runtime — separate Behaviour-Runtime-Welle
 */
sysml2Model("OrderProcessing") {

    // ── PartDefinitions für `represents`-Targets ────────────────────────
    partDef("Customer")
    partDef("OrderSystem")
    partDef("Warehouse")

    // ── V2.0.16: Activity Partitions (Swimlanes) ────────────────────────
    val customerLane = activityPartition("Customer", represents = "Customer")
    val orderSysLane = activityPartition("OrderSystem", represents = "OrderSystem")
    val warehouseLane = activityPartition("Warehouse", represents = "Warehouse")

    // ── Customer Lane ───────────────────────────────────────────────────
    val initial = initialNode(partition = customerLane)
    val placeOrder =
        actionDef(
            "PlaceOrder",
            action = "submit(order)",
            partition = customerLane,
            pins = listOf(ActionPin("orderDetails", typeId = "Order", direction = PinDirection.Output)),
        )

    // ── OrderSystem Lane ────────────────────────────────────────────────
    val validate =
        actionDef(
            "ValidateOrder",
            action = "validate(order)",
            partition = orderSysLane,
            pins =
                listOf(
                    ActionPin("orderDetails", typeId = "Order", direction = PinDirection.Input),
                    ActionPin("validation", typeId = "Bool", direction = PinDirection.Output),
                ),
        )
    val decide = decisionNode("valid?", partition = orderSysLane)
    val pay =
        actionDef(
            "ProcessPayment",
            action = "charge(order.total)",
            partition = orderSysLane,
            pins = listOf(ActionPin("validation", typeId = "Bool", direction = PinDirection.Input)),
        )
    val cancel =
        actionDef(
            "CancelOrder",
            action = "notify(order, 'cancelled')",
            partition = orderSysLane,
            pins = listOf(ActionPin("validation", typeId = "Bool", direction = PinDirection.Input)),
        )

    // ── Warehouse Lane ──────────────────────────────────────────────────
    val reserve =
        actionDef(
            "ReserveInventory",
            action = "reserve(order.items)",
            partition = warehouseLane,
            pins = listOf(ActionPin("orderDetails", typeId = "Order", direction = PinDirection.Input)),
        )
    val ship =
        actionDef(
            "ShipOrder",
            action = "dispatch(order)",
            partition = warehouseLane,
            pins = listOf(ActionPin("inventory", typeId = "Inventory", direction = PinDirection.Input)),
        )
    val finalN = finalNode(partition = warehouseLane)
    val flowFinal = flowFinalNode(partition = warehouseLane)

    // ── Control Flows (token-passing edges) ─────────────────────────────
    controlFlow("start", initial, placeOrder)
    controlFlow("placed", placeOrder, validate)
    controlFlow("validated", validate, decide)
    controlFlow("yes", decide, pay, guard = "valid")
    controlFlow("payToReserve", pay, reserve)
    controlFlow("reserveToShip", reserve, ship)
    controlFlow("end", ship, finalN)
    controlFlow("no", decide, cancel, guard = "!valid")
    controlFlow("cancelEnd", cancel, flowFinal)

    // ── Object Flow (token carries a typed object) ──────────────────────
    objectFlow("carryOrder", placeOrder, validate, objectType = "Order")

    // ── Activity Diagram ─────────────────────────────────────────────────
    actDiagram("Order Processing — workflow") {
        include(initial)
        include(placeOrder)
        include(validate)
        include(decide)
        include(pay)
        include(cancel)
        include(reserve)
        include(ship)
        include(finalN)
        include(flowFinal)
    }
}
