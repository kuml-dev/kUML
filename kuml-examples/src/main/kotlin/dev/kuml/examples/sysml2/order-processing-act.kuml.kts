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
sysml2Model(name = "OrderProcessing") {

    // ── PartDefinitions für `represents`-Targets ────────────────────────
    partDef(name = "Customer")
    partDef(name = "OrderSystem")
    partDef(name = "Warehouse")

    // ── V2.0.16: Activity Partitions (Swimlanes) ────────────────────────
    val customerLane = activityPartition(name = "Customer", represents = "Customer")
    val orderSysLane = activityPartition(name = "OrderSystem", represents = "OrderSystem")
    val warehouseLane = activityPartition(name = "Warehouse", represents = "Warehouse")

    // ── Customer Lane ───────────────────────────────────────────────────
    val initial = initialNode(partition = customerLane)
    val placeOrder =
        actionDef(
            name = "PlaceOrder",
            action = "submit(order)",
            partition = customerLane,
            pins = listOf(ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Output)),
        )

    // ── OrderSystem Lane ────────────────────────────────────────────────
    val validate =
        actionDef(
            name = "ValidateOrder",
            action = "validate(order)",
            partition = orderSysLane,
            pins =
                listOf(
                    ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Input),
                    ActionPin(name = "validation", typeId = "Bool", direction = PinDirection.Output),
                ),
        )
    val decide = decisionNode(name = "valid?", partition = orderSysLane)
    val pay =
        actionDef(
            name = "ProcessPayment",
            action = "charge(order.total)",
            partition = orderSysLane,
            pins = listOf(ActionPin(name = "validation", typeId = "Bool", direction = PinDirection.Input)),
        )
    val cancel =
        actionDef(
            name = "CancelOrder",
            action = "notify(order, 'cancelled')",
            partition = orderSysLane,
            pins = listOf(ActionPin(name = "validation", typeId = "Bool", direction = PinDirection.Input)),
        )

    // ── Warehouse Lane ──────────────────────────────────────────────────
    val reserve =
        actionDef(
            name = "ReserveInventory",
            action = "reserve(order.items)",
            partition = warehouseLane,
            pins = listOf(ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Input)),
        )
    val ship =
        actionDef(
            name = "ShipOrder",
            action = "dispatch(order)",
            partition = warehouseLane,
            pins = listOf(ActionPin(name = "inventory", typeId = "Inventory", direction = PinDirection.Input)),
        )
    val finalN = finalNode(partition = warehouseLane)
    val flowFinal = flowFinalNode(partition = warehouseLane)

    // ── Control Flows (token-passing edges) ─────────────────────────────
    controlFlow(name = "start", source = initial, target = placeOrder)
    controlFlow(name = "placed", source = placeOrder, target = validate)
    controlFlow(name = "validated", source = validate, target = decide)
    controlFlow(name = "yes", source = decide, target = pay, guard = "valid")
    controlFlow(name = "payToReserve", source = pay, target = reserve)
    controlFlow(name = "reserveToShip", source = reserve, target = ship)
    controlFlow(name = "end", source = ship, target = finalN)
    controlFlow(name = "no", source = decide, target = cancel, guard = "!valid")
    controlFlow(name = "cancelEnd", source = cancel, target = flowFinal)

    // ── Object Flow (token carries a typed object) ──────────────────────
    objectFlow(name = "carryOrder", source = placeOrder, target = validate, objectType = "Order")

    // ── Activity Diagram ─────────────────────────────────────────────────
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
