@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Order Processing — SysML 2 Activity Diagram example (V2.0.10 MVP).
 *
 * Illustriert die V2.0.10-Oberfläche end-to-end mit allen sieben
 * Aktivitäts-Knoten-Kinds und beiden Edge-Kinds:
 *  - **Initial** (gefüllter Kreis) als Start.
 *  - **Action** `ValidateOrder` als reguläre Aktion mit Body.
 *  - **Decision** (Raute) `valid?` mit zwei guarded outgoing Control Flows.
 *  - **Fork** (Bar) `Split` — splittet in zwei parallele Branches.
 *  - **Action**s `ProcessPayment` und `ReserveInventory` als parallele Branches.
 *  - **Join** (Bar) `Sync` — synchronisiert die parallelen Branches.
 *  - **Action** `ShipOrder` als finale Aktion.
 *  - **Final** (Donut) für das normale Ende der Activity.
 *  - **Action** `CancelOrder` für den Fehler-Pfad.
 *  - **FlowFinal** (Kreis mit X) für das Ende des Cancellation-Tokens.
 *
 * Edges:
 *  - **Control Flows** zwischen allen Knoten — die token-tragenden Edges.
 *  - **Ein Object Flow** vom `ValidateOrder` zur `Decision`, der den
 *    `Order`-Datensatz transportiert (`objectType = "Order"`).
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
 * beide Endpunkte sichtbar sind.
 *
 * Out of V2.0.10 scope (siehe Wave-Plan):
 *  - Activity-Partition (Swimlanes)
 *  - Interruptible regions
 *  - Pin-Notation auf Aktionen
 *  - Stream-Flow / Multicast-Semantics auf Object Flow
 *  - `[guard]` / `[ObjectType]` Edge-Labels in SVG und TikZ (gleiche
 *    Limitation wie UC / REQ / STM — die synthetische `KumlDiagram`-Hülle
 *    hat keine `UmlRelationship`-Elemente für `ControlFlowUsage` /
 *    `ObjectFlowUsage`)
 *  - Live Token-Flow-Runtime — separate Behaviour-Runtime-Welle
 *  - PNG-Export für SysML 2 ACT
 */
sysml2Model("OrderProcessing") {

    // ── Activity nodes ───────────────────────────────────────────────────
    val initial = initialNode()
    val validate = actionDef("ValidateOrder", action = "validate(order)")
    val decide = decisionNode("valid?")
    val fork = forkNode("Split")
    val pay = actionDef("ProcessPayment", action = "charge(order.total)")
    val reserve = actionDef("ReserveInventory", action = "reserve(order.items)")
    val join = joinNode("Sync")
    val ship = actionDef("ShipOrder", action = "dispatch(order)")
    val finalN = finalNode()
    val cancel = actionDef("CancelOrder", action = "notify(order, 'cancelled')")
    val flowFinal = flowFinalNode()

    // ── Control Flows (token-passing edges) ──────────────────────────────
    controlFlow("start", initial, validate)
    controlFlow("validated", validate, decide)
    controlFlow("yes", decide, fork, guard = "valid")
    controlFlow("forkToPay", fork, pay)
    controlFlow("forkToReserve", fork, reserve)
    controlFlow("payToJoin", pay, join)
    controlFlow("reserveToJoin", reserve, join)
    controlFlow("joinToShip", join, ship)
    controlFlow("end", ship, finalN)
    controlFlow("no", decide, cancel, guard = "!valid")
    controlFlow("cancelEnd", cancel, flowFinal)

    // ── Object Flow (token carries a typed object) ───────────────────────
    objectFlow("carryOrder", validate, decide, objectType = "Order")

    // ── Activity Diagram ─────────────────────────────────────────────────
    actDiagram("Order Processing — workflow") {
        include(initial)
        include(validate)
        include(decide)
        include(fork)
        include(pay)
        include(reserve)
        include(join)
        include(ship)
        include(finalN)
        include(cancel)
        include(flowFinal)
    }
}
