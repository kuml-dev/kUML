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
sysml2Model("Car2xIntersectionScenario") {

    // ── States ────────────────────────────────────────────────────────────────

    // Initial pseudo-state — auto-fires into Idle on startup (no trigger needed).
    val initial = stateDef("Initial", isInitial = true)

    val idle = stateDef("Idle")

    val approaching =
        stateDef(
            "Approaching",
            entryAction = "activateV2X()",
            exitAction = "updateTTC()",
        )

    val negotiating =
        stateDef(
            "Negotiating",
            entryAction = "broadcastCAM(ego)",
            doAction = "monitorRSU()",
        )

    val crossing =
        stateDef(
            "Crossing",
            entryAction = "notify('entering intersection')",
            exitAction = "recordCrossing()",
        )

    // Departed is NOT isFinal because it has an outgoing `reset` transition.
    // It semantically marks "past the intersection" but the cycle can repeat.
    val departed = stateDef("Departed")

    // ── Transitions ───────────────────────────────────────────────────────────

    // Auto-fire from initial pseudo-state into Idle on startup.
    transition("init", initial, idle)

    // Vehicle detected ahead — TTC > 10s means we have time to approach normally.
    // Guard uses event payload field `ttc` (time-to-collision in seconds).
    transition(
        "detect",
        idle,
        approaching,
        trigger = "vehicleDetected",
        guard = "event.ttc > 10",
        id = "transition:Idle::Approaching:vehicleDetected",
    )

    // CAM (Cooperative Awareness Message) received while approaching.
    // TTC <= 10s means we must begin negotiation now.
    transition(
        "camReceived",
        approaching,
        negotiating,
        trigger = "camReceived",
        guard = "event.ttc <= 10",
        id = "transition:Approaching::Negotiating:camReceived",
    )

    // Right-of-way granted by RSU (Road-Side Unit) or peer vehicle.
    transition(
        "grantCrossing",
        negotiating,
        crossing,
        trigger = "rightOfWay",
        effect = "log('crossing granted')",
        id = "transition:Negotiating::Crossing:rightOfWay",
    )

    // Conflict detected during negotiation — yield and return to Idle for retry.
    transition(
        "conflict",
        negotiating,
        idle,
        trigger = "conflict",
        effect = "log('conflict detected, yielding')",
        id = "transition:Negotiating::Idle:conflict",
    )

    // Intersection cleared — ego vehicle has fully passed the stop line.
    transition(
        "cleared",
        crossing,
        departed,
        trigger = "cleared",
        effect = "log('intersection cleared')",
        id = "transition:Crossing::Departed:cleared",
    )

    // Scenario reset — Departed loops back to Idle for the next cycle.
    transition(
        "reset",
        departed,
        idle,
        trigger = "reset",
        id = "transition:Departed::Idle:reset",
    )

    // ── State Transition Diagram ──────────────────────────────────────────────
    stmDiagram("Car2x — Intersection Scenario") {
        include(initial)
        include(idle)
        include(approaching)
        include(negotiating)
        include(crossing)
        include(departed)
    }

    // ── SysML 2 BDD — V2X System Components ──────────────────────────────────
    //
    // Attribute types
    val speed = attributeDef("Speed")
    val angle = attributeDef("Angle")
    val real = attributeDef("Real")
    val length = attributeDef("Length")

    // Port type
    val v2xLink = portDef("V2XLink")

    // Part definitions — the three key V2X actors
    val egoVehicle =
        partDef("EgoVehicle") {
            attribute("speed", typeId = speed.id)
            attribute("heading", typeId = angle.id)
            attribute("ttc", typeId = real.id)
            port("v2x", typeId = v2xLink.id)
        }

    val targetVehicle =
        partDef("TargetVehicle") {
            attribute("speed", typeId = speed.id)
            attribute("heading", typeId = angle.id)
            port("v2x", typeId = v2xLink.id)
        }

    val rsu =
        partDef("RSU") {
            attribute("range", typeId = length.id)
            port("v2x", typeId = v2xLink.id)
        }

    // ── Block Definition Diagram ──────────────────────────────────────────────
    bdd("Car2x System") {
        include(egoVehicle)
        include(targetVehicle)
        include(rsu)
    }
}
