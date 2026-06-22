@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Keysight Car2x / V2X — Intersection Scenario State Machine (V2.0.29 showcase)
 *
 * Models the V2X cooperative intersection-negotiation logic of an ego vehicle
 * equipped with a C-V2X / ETSI ITS G5 stack. This is a representative test
 * scenario for Keysight's Car2x test frameworks (UXM Wireless Test Set,
 * V2X Scenario Toolset) and validates the V2.0.27 MCP runtime tools.
 *
 * Standards context:
 *  - ETSI EN 302 637-2: Cooperative Awareness Messages (CAM)
 *  - ETSI EN 302 637-3: Decentralised Environmental Notification Messages (DENM)
 *  - ETSI TS 102 724: Intelligent Transportation Systems (ITS) — use case layer
 *  - ISO 21217: ITS Station architecture (ITS-S)
 *
 * States:
 *  Idle (initial) → Approaching → Negotiating → Crossing → Departed (final)
 *
 * The state machine is parametric: guard expressions reference payload fields
 * (`event.ttc`) that are supplied by the events JSON file at simulation time.
 *
 * Runnable via:
 *   kuml simulate car2x-scenario-stm.kuml.kts \
 *     --events car2x-events-happy.json \
 *     --out /tmp/car2x-happy.trace.json
 *
 * Renderable via:
 *   kuml render car2x-scenario-stm.kuml.kts --format svg --output car2x.svg
 */
sysml2Model(name = "Car2xIntersectionScenario") {

    // ── States ────────────────────────────────────────────────────────────────

    // Initial pseudo-state — auto-fires into Idle on startup (no trigger needed).
    val initial = stateDef(name = "Initial", isInitial = true)

    val idle = stateDef(name = "Idle")

    val approaching =
        stateDef(
            name = "Approaching",
            entryAction = "activateV2X()",
            exitAction = "updateTTC()",
        )

    val negotiating =
        stateDef(
            name = "Negotiating",
            entryAction = "broadcastCAM(ego)",
            doAction = "monitorRSU()",
        )

    val crossing =
        stateDef(
            name = "Crossing",
            entryAction = "notify('entering intersection')",
            exitAction = "recordCrossing()",
        )

    // Departed is NOT isFinal because it has an outgoing `reset` transition.
    // It semantically marks "past the intersection" but the cycle can repeat.
    val departed = stateDef(name = "Departed")

    // ── Transitions ───────────────────────────────────────────────────────────

    // Auto-fire from initial pseudo-state into Idle on startup.
    transition(name = "init", source = initial, target = idle)

    // Vehicle detected ahead — TTC > 10s means we have time to approach normally.
    // Guard uses event payload field `ttc` (time-to-collision in seconds).
    transition(
        name = "detect",
        source = idle,
        target = approaching,
        trigger = "vehicleDetected",
        guard = "event.ttc > 10",
        id = "transition:Idle::Approaching:vehicleDetected",
    )

    // CAM (Cooperative Awareness Message) received while approaching.
    // TTC <= 10s means we must begin negotiation now.
    transition(
        name = "camReceived",
        source = approaching,
        target = negotiating,
        trigger = "camReceived",
        guard = "event.ttc <= 10",
        id = "transition:Approaching::Negotiating:camReceived",
    )

    // Right-of-way granted by RSU (Road-Side Unit) or peer vehicle.
    transition(
        name = "grantCrossing",
        source = negotiating,
        target = crossing,
        trigger = "rightOfWay",
        effect = "log('crossing granted')",
        id = "transition:Negotiating::Crossing:rightOfWay",
    )

    // Conflict detected during negotiation — yield and return to Idle for retry.
    transition(
        name = "conflict",
        source = negotiating,
        target = idle,
        trigger = "conflict",
        effect = "log('conflict detected, yielding')",
        id = "transition:Negotiating::Idle:conflict",
    )

    // Intersection cleared — ego vehicle has fully passed the stop line.
    transition(
        name = "cleared",
        source = crossing,
        target = departed,
        trigger = "cleared",
        effect = "log('intersection cleared')",
        id = "transition:Crossing::Departed:cleared",
    )

    // Scenario reset — Departed loops back to Idle for the next cycle.
    transition(
        name = "reset",
        source = departed,
        target = idle,
        trigger = "reset",
        id = "transition:Departed::Idle:reset",
    )

    // ── State Transition Diagram ──────────────────────────────────────────────
    stmDiagram(name = "Car2x — Intersection Scenario") {
        include(state = initial)
        include(state = idle)
        include(state = approaching)
        include(state = negotiating)
        include(state = crossing)
        include(state = departed)
    }

    // ── SysML 2 BDD — V2X System Components ──────────────────────────────────
    //
    // Attribute types
    val speed = attributeDef(name = "Speed")
    val angle = attributeDef(name = "Angle")
    val real = attributeDef(name = "Real")
    val length = attributeDef(name = "Length")

    // Port type
    val v2xLink = portDef(name = "V2XLink")

    // Part definitions — the three key V2X actors
    val egoVehicle =
        partDef(name = "EgoVehicle") {
            attribute(name = "speed", typeId = speed.id)
            attribute(name = "heading", typeId = angle.id)
            attribute(name = "ttc", typeId = real.id)
            port(name = "v2x", typeId = v2xLink.id)
        }

    val targetVehicle =
        partDef(name = "TargetVehicle") {
            attribute(name = "speed", typeId = speed.id)
            attribute(name = "heading", typeId = angle.id)
            port(name = "v2x", typeId = v2xLink.id)
        }

    val rsu =
        partDef(name = "RSU") {
            attribute(name = "range", typeId = length.id)
            port(name = "v2x", typeId = v2xLink.id)
        }

    // ── Block Definition Diagram ──────────────────────────────────────────────
    bdd(name = "Car2x System") {
        include(definition = egoVehicle)
        include(definition = targetVehicle)
        include(definition = rsu)
    }
}
