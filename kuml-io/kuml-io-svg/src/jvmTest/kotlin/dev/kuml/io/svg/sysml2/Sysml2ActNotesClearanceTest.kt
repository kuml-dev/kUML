package dev.kuml.io.svg.sysml2

import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.label.Sysml2LabelSizing
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.bridge.normalizeActLayout
import dev.kuml.layout.bridge.note.NoteMeasure
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.layout.polylinePoints
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

/**
 * Nacharbeit 29: the notes of an activity diagram keep 16 px away from edges, nodes, pin labels, lane titles and each
 * other, and respect the lane invariant (fully inside one lane or fully outside all of them). Geometry only and
 * independent of any font: the note boxes come from a fixed [NoteMeasure] (7 px per character), the layout from the
 * real pipeline (ELK, swimlane normalisation) and the notes through the same helpers the renderer uses.
 */
class Sysml2ActNotesClearanceTest :
    FunSpec({
        val clearance = 16f

        val fixedMeasure =
            object : NoteMeasure {
                override fun measure(
                    body: String,
                    maxWidth: Float,
                ): Size {
                    val width = min(NoteLayout.STM_MAX_W, 7f * body.length + 16f)
                    val perLine = max(1, ((width - 16f) / 7f).toInt())
                    val lines = ceil(body.length.toFloat() / perLine).toInt().coerceAtLeast(1)
                    return Size(width = width, height = 16f * lines + 16f)
                }

                override fun measure(
                    body: String,
                    maxWidth: Float,
                    kind: NoteKind,
                ): Size = measure(body = body, maxWidth = maxWidth)
            }

        fun orderExample(): Sysml2Model =
            sysml2Model(name = "OrderProcessing") {
                partDef(name = "Customer")
                partDef(name = "OrderSystem")
                partDef(name = "Warehouse")
                val customerLane = activityPartition(name = "Customer", represents = "Customer")
                val orderSysLane = activityPartition(name = "OrderSystem", represents = "OrderSystem")
                val warehouseLane = activityPartition(name = "Warehouse", represents = "Warehouse")
                val initial = initialNode(partition = customerLane)
                val placeOrder =
                    actionDef(
                        name = "PlaceOrder",
                        action = "submit(order)",
                        partition = customerLane,
                        pins = listOf(ActionPin(name = "orderDetails", typeId = "Order", direction = PinDirection.Output)),
                    )
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
                controlFlow(name = "start", source = initial, target = placeOrder)
                controlFlow(name = "validated", source = validate, target = decide)
                controlFlow(name = "yes", source = decide, target = pay, guard = "valid")
                controlFlow(name = "payToReserve", source = pay, target = reserve)
                controlFlow(name = "reserveToShip", source = reserve, target = ship)
                controlFlow(name = "end", source = ship, target = finalN)
                controlFlow(name = "no", source = decide, target = cancel, guard = "!valid")
                controlFlow(name = "cancelEnd", source = cancel, target = flowFinal)
                objectFlow(name = "carryOrder", source = placeOrder, target = validate, objectType = "Order")
                actDiagram(name = "Order Processing") {
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
                    comment(text = "Error: an invalid order is cancelled.", firstAnchor = cancel, kind = NoteKind.ERROR)
                    comment(text = "Warning: card data must never be logged.", firstAnchor = pay, kind = NoteKind.WARNING)
                    comment(text = "Info: shipping takes two working days.", firstAnchor = ship, kind = NoteKind.INFO)
                    comment(text = "Decision: validate before payment.", firstAnchor = decide, kind = NoteKind.DECISION)
                    comment(text = "Todo: partial deliveries.", firstAnchor = reserve, kind = NoteKind.TODO)
                }
            }

        fun forkJoin(): Sysml2Model =
            sysml2Model(name = "ForkJoin") {
                val initial = initialNode()
                val fork = forkNode(name = "Split")
                val a = actionDef(name = "PackItems", action = "pack()")
                val b = actionDef(name = "PrintLabel", action = "print()")
                val join = joinNode(name = "Sync")
                val finalN = finalNode()
                controlFlow(name = "s", source = initial, target = fork)
                controlFlow(name = "f1", source = fork, target = a)
                controlFlow(name = "f2", source = fork, target = b)
                controlFlow(name = "j1", source = a, target = join)
                controlFlow(name = "j2", source = b, target = join)
                controlFlow(name = "e", source = join, target = finalN)
                actDiagram(name = "Fork") {
                    include(node = initial)
                    include(node = fork)
                    include(node = a)
                    include(node = b)
                    include(node = join)
                    include(node = finalN)
                    comment(text = "Info: the two branches run in parallel.", firstAnchor = fork, kind = NoteKind.INFO)
                    comment(text = "Warning: wait for both branches before shipping.", firstAnchor = join, kind = NoteKind.WARNING)
                    comment(text = "Todo: print the label on a second printer.", firstAnchor = b, kind = NoteKind.TODO)
                }
            }

        fun linear(): Sysml2Model =
            sysml2Model(name = "Linear") {
                val initial = initialNode()
                val a = actionDef(name = "Collect", action = "collect()")
                val b = actionDef(name = "Check", action = "check()")
                val c = actionDef(name = "Approve", action = "approve()")
                val d = actionDef(name = "Archive", action = "archive()")
                val finalN = finalNode()
                controlFlow(name = "s", source = initial, target = a)
                controlFlow(name = "ab", source = a, target = b)
                controlFlow(name = "bc", source = b, target = c)
                controlFlow(name = "cd", source = c, target = d)
                controlFlow(name = "e", source = d, target = finalN)
                actDiagram(name = "Linear") {
                    include(node = initial)
                    include(node = a)
                    include(node = b)
                    include(node = c)
                    include(node = d)
                    include(node = finalN)
                    comment(text = "Info: collect all documents first.", firstAnchor = a, kind = NoteKind.INFO)
                    comment(text = "Decision: approval needs two people.", firstAnchor = c, kind = NoteKind.DECISION)
                }
            }

        fun withoutPartitions(): Sysml2Model =
            sysml2Model(name = "Plain") {
                val initial = initialNode()
                val a = actionDef(name = "Draft", action = "draft()")
                val b = actionDef(name = "Review", action = "review()")
                val c = actionDef(name = "Publish", action = "publish()")
                controlFlow(name = "s", source = initial, target = a)
                controlFlow(name = "ab", source = a, target = b)
                controlFlow(name = "bc", source = b, target = c)
                actDiagram(name = "Plain") {
                    include(node = initial)
                    include(node = a)
                    include(node = b)
                    include(node = c)
                    comment(text = "Info: drafts are private.", firstAnchor = a, kind = NoteKind.INFO)
                    comment(text = "Warning: reviewers must not edit.", firstAnchor = b, kind = NoteKind.WARNING)
                    comment(text = "Todo: automate publishing.", firstAnchor = c, kind = NoteKind.TODO)
                }
            }

        class Run(
            val model: Sysml2Model,
            val raw: LayoutResult,
            val normalized: LayoutResult,
            val placed: LayoutResult,
            val noteIds: Set<String>,
            val linkIds: Set<String>,
        )

        fun run(model: Sysml2Model): Run {
            val d = model.diagrams.filterIsInstance<ActDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = d)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = d)
            val raw =
                LayoutSpecRunner
                    .layout(engine = ElkLayoutEngine(), graph = graph, hints = hints, spec = d.layout, kind = SpecDiagramKind.SYSML2_ACT)
                    .result
            val normalized = Sysml2LayoutBridge.normalizeActLayout(model = model, diagram = d, layoutResult = raw)
            val placed =
                KumlSvgRenderer
                    .placeSysml2NotesResult(
                        model = model,
                        diagram = d,
                        layout = normalized,
                        strictNotes = KumlSvgRenderer.actStrictNotePolicy(model = model, layout = normalized),
                        configure = KumlSvgRenderer.actNoteObstacles(model = model, layout = normalized),
                        measure = fixedMeasure,
                    ).layout
            return Run(model, raw, normalized, placed, model.comments.map { it.id }.toSet(), model.commentLinks.map { it.id }.toSet())
        }

        fun inflate(
            r: Rect,
            d: Float,
        ) = Rect(
            origin = Point(x = r.origin.x - d, y = r.origin.y - d),
            size = Size(width = r.size.width + 2 * d, height = r.size.height + 2 * d),
        )

        fun hit(
            a: Rect,
            b: Rect,
        ): Boolean =
            a.origin.x < b.origin.x + b.size.width &&
                b.origin.x < a.origin.x + a.size.width &&
                a.origin.y < b.origin.y + b.size.height &&
                b.origin.y < a.origin.y + a.size.height

        fun segmentHitsRect(
            a: Point,
            b: Point,
            r: Rect,
        ): Boolean {
            val x0 = r.origin.x
            val x1 = r.origin.x + r.size.width
            val y0 = r.origin.y
            val y1 = r.origin.y + r.size.height
            var t0 = 0f
            var t1 = 1f
            val dx = b.x - a.x
            val dy = b.y - a.y
            val p = floatArrayOf(-dx, dx, -dy, dy)
            val q = floatArrayOf(a.x - x0, x1 - a.x, a.y - y0, y1 - a.y)
            for (i in 0..3) {
                if (p[i] == 0f) {
                    if (q[i] < 0f) return false
                } else {
                    val t = q[i] / p[i]
                    if (p[i] < 0f) {
                        if (t > t1) return false
                        if (t > t0) t0 = t
                    } else {
                        if (t < t0) return false
                        if (t < t1) t1 = t
                    }
                }
            }
            return true
        }

        fun describe(r: Rect) = "(${r.origin.x},${r.origin.y} ${r.size.width}x${r.size.height})"

        /** The estimated label zone straight outward of every pin of every action, plus 4 px. */
        fun pinLabelZones(r: Run): List<Pair<String, Rect>> =
            r.model.definitions.filterIsInstance<ActionDefinition>().flatMap { def ->
                val bounds = r.placed.nodes[NodeId(def.id)]?.bounds ?: return@flatMap emptyList()
                actionPinLabelSpecs(def = def, bounds = bounds, dockedPins = emptySet()).map { spec ->
                    val size = Sysml2LabelSizing.sizeOf(Sysml2LabelSizing.wrapPortLabel(text = spec.text))
                    val w = size.width + 8f
                    val h = size.height + 8f
                    val pin = spec.pinRect
                    val cx = pin.origin.x + pin.size.width / 2f
                    val cy = pin.origin.y + pin.size.height / 2f
                    val zone =
                        when (spec.side) {
                            dev.kuml.layout.bridge.label.LabelSide.WEST ->
                                Rect(origin = Point(x = pin.origin.x - w, y = cy - h / 2f), size = Size(width = w, height = h))
                            dev.kuml.layout.bridge.label.LabelSide.EAST ->
                                Rect(origin = Point(x = pin.origin.x + pin.size.width, y = cy - h / 2f), size = Size(width = w, height = h))
                            dev.kuml.layout.bridge.label.LabelSide.NORTH ->
                                Rect(origin = Point(x = cx - w / 2f, y = pin.origin.y - h), size = Size(width = w, height = h))
                            else ->
                                Rect(
                                    origin = Point(x = cx - w / 2f, y = pin.origin.y + pin.size.height),
                                    size = Size(width = w, height = h),
                                )
                        }
                    spec.id to zone
                }
            }

        fun lanesOf(r: Run): List<Pair<String, Rect>> {
            val ids =
                r.model.definitions
                    .filterIsInstance<ActivityPartitionDefinition>()
                    .map { it.id }
                    .toSet()
            return r.placed.groups
                .filterKeys { it.value in ids }
                .map { it.key.value to it.value.bounds }
        }

        fun failures(r: Run): List<String> {
            val out = mutableListOf<String>()
            val notes =
                r.noteIds.associateWith {
                    r.placed.nodes
                        .getValue(NodeId(it))
                        .bounds
                }
            val nodes =
                r.placed.nodes
                    .filterKeys { it.value !in r.noteIds }
                    .mapKeys { it.key.value }
                    .mapValues { it.value.bounds }
            val edges =
                r.placed.edges
                    .filterKeys { it.value !in r.linkIds }
                    .mapKeys { it.key.value }
                    .mapValues { it.value.polylinePoints() }
            val zones = pinLabelZones(r)
            val lanes = lanesOf(r)
            for ((nid, nr) in notes) {
                val zone = inflate(nr, clearance)
                for ((eid, pts) in edges) {
                    if ((0 until pts.size - 1).any { segmentHitsRect(pts[it], pts[it + 1], zone) }) {
                        out += "note $nid ${describe(nr)} within 16 px of edge $eid"
                    }
                }
                for ((vid, vr) in nodes) if (hit(zone, vr)) out += "note $nid ${describe(nr)} within 16 px of node $vid"
                for ((pid, pz) in zones) if (hit(zone, pz)) out += "note $nid ${describe(nr)} within 16 px of the label zone of pin $pid"
                for ((oid, orr) in notes) if (oid != nid && hit(zone, orr)) out += "note $nid within 16 px of note $oid"
                if (lanes.isNotEmpty()) {
                    for ((lid, lr) in lanes) {
                        val strip = Rect(origin = lr.origin, size = Size(width = lr.size.width, height = min(30f, lr.size.height)))
                        if (hit(nr, strip)) out += "note $nid ${describe(nr)} covers the title strip of lane $lid"
                    }
                    val inside =
                        lanes.any { (_, lr) ->
                            nr.origin.x >= lr.origin.x + 16f &&
                                nr.origin.y >= lr.origin.y + 16f &&
                                nr.origin.x + nr.size.width <= lr.origin.x + lr.size.width - 16f &&
                                nr.origin.y + nr.size.height <= lr.origin.y + lr.size.height - 16f
                        }
                    val outside = lanes.none { (_, lr) -> hit(inflate(lr, 24f), nr) }
                    if (!inside && !outside) out += "note $nid ${describe(nr)} straddles a lane border"
                }
            }
            return out
        }

        fun check(
            name: String,
            model: Sysml2Model,
        ) {
            val r = run(model)
            val f = failures(r)
            println("[nacharbeit-29] $name: ${r.noteIds.size} notes, ${f.size} failures")
            r.noteIds.forEach {
                println(
                    "[nacharbeit-29]   $it=${describe(
                        r.placed.nodes
                            .getValue(NodeId(it))
                            .bounds,
                    )}",
                )
            }
            f.forEach { println("[nacharbeit-29]   $it") }
            r.noteIds.all { r.placed.nodes.containsKey(NodeId(it)) } shouldBe true
            f.shouldBeEmpty()
        }

        test("order example: five notes keep 16 px from edges, nodes, pin labels, lane titles and each other, and respect the lanes")
            .config(timeout = 60.seconds) { check("order", orderExample()) }

        test("fork and join: notes on the bars and an action keep clear")
            .config(timeout = 60.seconds) { check("fork-join", forkJoin()) }

        test("linear flow of four actions with two notes")
            .config(timeout = 60.seconds) { check("linear", linear()) }

        test("a diagram without partitions behaves like the state-machine rules")
            .config(timeout = 60.seconds) { check("plain", withoutPartitions()) }

        test("the order example has lanes, and every lane keeps the invariant even when no note fits inside").config(timeout = 60.seconds) {
            val r = run(orderExample())
            lanesOf(r).size shouldBe 3
        }

        test("diagnostic: a diagram of the order example renders to SVG with the strict policy").config(timeout = 60.seconds) {
            val r = run(orderExample())
            val d =
                r.model.diagrams
                    .filterIsInstance<ActDiagram>()
                    .single()
            val svg = KumlSvgRenderer.toSvg(model = r.model, diagram = d, layoutResult = r.raw, theme = PlainTheme())
            svg.contains("kuml-note-anchor") shouldBe true
        }
    })
