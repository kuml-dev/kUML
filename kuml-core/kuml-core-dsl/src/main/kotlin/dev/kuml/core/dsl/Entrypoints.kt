package dev.kuml.core.dsl

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.dsl.ClassDiagramBuilder
import dev.kuml.uml.dsl.ComponentDiagramBuilder
import dev.kuml.uml.dsl.SequenceDiagramBuilder
import dev.kuml.uml.dsl.StateDiagramBuilder
import dev.kuml.uml.dsl.UseCaseDiagramBuilder

/**
 * Creates a kUML diagram.
 *
 * This is the primary top-level entry point for all diagram types.
 * Default imports in `*.kuml.kts` scripts make this available without
 * an explicit import.
 *
 * Usage:
 * ```kotlin
 * diagram(name = "Order System", type = DiagramType.CLASS) {
 *     // elements will be added here in Phase 1
 * }
 * ```
 *
 * @param name Human-readable name displayed in diagram titles.
 * @param type Diagram type — determines renderer and available elements.
 * @param block Optional builder lambda for adding diagram elements.
 * @return The built [KumlDiagram].
 */
fun diagram(
    name: String,
    type: DiagramType = DiagramType.CLASS,
    block: DiagramBuilder.() -> Unit = {},
): KumlDiagram = DiagramBuilder(name = name, type = type).apply(block).build()

/**
 * Creates a UML class diagram.
 *
 * All structural UML element builders are available in the [block] lambda:
 * [dev.kuml.uml.dsl.classOf], [dev.kuml.uml.dsl.interfaceOf], [dev.kuml.uml.dsl.enumOf],
 * [dev.kuml.uml.dsl.packageOf], [dev.kuml.uml.dsl.association],
 * [dev.kuml.uml.dsl.generalization], [dev.kuml.uml.dsl.realization],
 * [dev.kuml.uml.dsl.dependency].
 *
 * Behavioural elements (state machines, interactions) are rejected with
 * an [IllegalArgumentException] at build time.
 *
 * Display options can be set directly on the builder:
 * ```kotlin
 * classDiagram("Domain Model") {
 *     showOperations = false
 *     val order = classOf("Order") { … }
 * }
 * ```
 *
 * @param name Human-readable diagram name.
 * @param block Builder lambda.
 * @return The built [KumlDiagram] with [dev.kuml.core.model.ClassDiagramConfig] attached.
 */
fun classDiagram(
    name: String,
    block: ClassDiagramBuilder.() -> Unit = {},
): KumlDiagram = ClassDiagramBuilder(name = name).apply(block).build()

/**
 * Creates a UML use-case diagram.
 *
 * Available builders in the [block]:
 * - [dev.kuml.uml.dsl.actor], [dev.kuml.uml.dsl.useCase], [dev.kuml.uml.dsl.subject]
 * - [dev.kuml.uml.dsl.include], [dev.kuml.uml.dsl.extend]
 * - [dev.kuml.uml.dsl.association] (for actor ↔ use-case connections)
 * - [dev.kuml.uml.dsl.generalization] (for actor or use-case specialisation)
 *
 * ```kotlin
 * useCaseDiagram("Checkout") {
 *     val customer = actor("Customer")
 *     val place    = useCase("Place Order")
 *     val validate = useCase("Validate Cart")
 *     val pay      = useCase("Apply Discount")
 *
 *     subject("Online Shop", place, validate, pay)
 *
 *     association(source = customer, target = place)
 *     include(base = place, addition = validate)
 *     extend(base = place, extension = pay, at = "PaymentChosen")
 * }
 * ```
 *
 * @param name Human-readable diagram name.
 * @param block Builder lambda.
 * @return The built [KumlDiagram] with [dev.kuml.core.model.UseCaseDiagramConfig] attached.
 */
fun useCaseDiagram(
    name: String,
    block: UseCaseDiagramBuilder.() -> Unit = {},
): KumlDiagram = UseCaseDiagramBuilder(name = name).apply(block).build()

/**
 * Creates a UML 2.x object diagram (V1.1).
 *
 * An object diagram is a snapshot — instances of classifiers with their slot
 * values, connected by links. Useful for documenting a specific configuration
 * the class diagram is meant to permit, or for capturing test fixtures
 * pictorially.
 *
 * Available builders inside the [block]:
 * - [dev.kuml.uml.dsl.ObjectDiagramBuilder.instanceOf] — create an instance of a classifier
 * - [dev.kuml.uml.dsl.ObjectDiagramBuilder.link] — connect two instances
 * - inside `instanceOf { … }`:
 *   `slot(feature, value)`, with value helpers
 *   [dev.kuml.uml.dsl.literal], [dev.kuml.uml.dsl.ref], [dev.kuml.uml.dsl.nullValue]
 *
 * ```kotlin
 * val customer = classOf("Customer") { attribute("name", type = "String") }
 * val order    = classOf("Order")    { attribute("amount", type = "BigDecimal") }
 *
 * objectDiagram("Order #42 snapshot") {
 *     val alice = instanceOf(customer, name = "alice") {
 *         slot(feature = "name", value = literal("Alice"))
 *     }
 *     val ord = instanceOf(order, name = "order42") {
 *         slot(feature = "amount", value = literal("19.95"))
 *     }
 *     link(from = alice, to = ord)
 * }
 * ```
 *
 * @param name Human-readable diagram name.
 * @param block Builder lambda.
 * @return The built [KumlDiagram] with [dev.kuml.core.model.ObjectDiagramConfig] attached.
 */
public fun objectDiagram(
    name: String,
    block: dev.kuml.uml.dsl.ObjectDiagramBuilder.() -> Unit = {},
): KumlDiagram =
    dev.kuml.uml.dsl
        .ObjectDiagramBuilder(name = name)
        .apply(block)
        .build()

/**
 * Creates a UML component diagram.
 *
 * Available builders in the [block]:
 * - [dev.kuml.uml.dsl.component] — top-level component (with nested `component { }`, `port`,
 *   `provides`, `requires` inside)
 * - [dev.kuml.uml.dsl.interfaceOf] — interfaces referenced by `provides` / `requires`
 * - [dev.kuml.uml.dsl.connect] — connectors between ports
 * - [dev.kuml.uml.dsl.dependency] — «use» dependencies between components
 *
 * ```kotlin
 * componentDiagram("Architecture") {
 *     val orderApi = interfaceOf("IOrderApi") { operation("placeOrder") }
 *     val order    = component("OrderService") { port("api"); provides(orderApi) }
 *     val invoice  = component("InvoiceService") { port("orderEvents"); requires(orderApi) }
 *     connect(end1 = order, port1 = "api", end2 = invoice, port2 = "orderEvents")
 * }
 * ```
 *
 * @param name Human-readable diagram name.
 * @param block Builder lambda.
 * @return The built [KumlDiagram] with [dev.kuml.core.model.ComponentDiagramConfig] attached.
 */
fun componentDiagram(
    name: String,
    block: ComponentDiagramBuilder.() -> Unit = {},
): KumlDiagram = ComponentDiagramBuilder(name = name).apply(block).build()

/**
 * Creates a UML state-machine diagram.
 *
 * The diagram contains exactly one [dev.kuml.uml.UmlStateMachine] —
 * named after the diagram — populated by the [block].
 *
 * Available builders:
 * - [dev.kuml.uml.dsl.state] — simple state with optional entry/exit/do
 * - [dev.kuml.uml.dsl.initialState], [dev.kuml.uml.dsl.finalState]
 * - [dev.kuml.uml.dsl.choice], [dev.kuml.uml.dsl.fork], [dev.kuml.uml.dsl.join], [dev.kuml.uml.dsl.junction]
 * - [dev.kuml.uml.dsl.compositeState] — composite with nested substates
 * - [dev.kuml.uml.dsl.transition] — transition between two vertices
 *
 * ```kotlin
 * stateDiagram("Order Lifecycle") {
 *     val init      = initialState()
 *     val draft     = state("Draft")
 *     val confirmed = state("Confirmed")
 *     val done      = finalState("Done")
 *
 *     transition(init,      draft)
 *     transition(draft,     confirmed) { trigger = "confirm()" }
 *     transition(confirmed, done)      { trigger = "ship()" }
 * }
 * ```
 *
 * @param name Human-readable diagram name (also the state-machine name).
 * @param block Builder lambda.
 * @return The built [KumlDiagram] with [dev.kuml.core.model.StateDiagramConfig] attached.
 */
fun stateDiagram(
    name: String,
    block: StateDiagramBuilder.() -> Unit = {},
): KumlDiagram = StateDiagramBuilder(name = name).apply(block).build()

/**
 * Creates a UML sequence diagram.
 *
 * The diagram contains exactly one [dev.kuml.uml.UmlInteraction] —
 * named after the diagram — populated by the [block].
 *
 * Available builders:
 * - [dev.kuml.uml.dsl.lifeline]
 * - [dev.kuml.uml.dsl.message] (+ `asyncMessage`, `reply`, `create`, `delete`)
 * - [dev.kuml.uml.dsl.fragment] / [dev.kuml.uml.dsl.alt] / [dev.kuml.uml.dsl.opt] /
 *   [dev.kuml.uml.dsl.loop] / [dev.kuml.uml.dsl.par] / [dev.kuml.uml.dsl.break_]
 *
 * Message sequence numbers are assigned automatically in DSL call order,
 * including messages inside nested fragments.
 *
 * ```kotlin
 * sequenceDiagram("Place Order") {
 *     val customer = lifeline("Customer") { isActor = true }
 *     val frontend = lifeline("Frontend")
 *     val backend  = lifeline("Backend")
 *
 *     message(customer, frontend, "submitOrder()")
 *     message(frontend, backend, "POST /orders")
 *     alt {
 *         branch(guard = "[valid]") { reply(backend, frontend, "201 Created") }
 *         branch(guard = "[invalid]") { reply(backend, frontend, "400 Bad Request") }
 *     }
 *     reply(frontend, customer, "confirmation")
 * }
 * ```
 *
 * @param name Human-readable diagram name (also the interaction name).
 * @return The built [KumlDiagram] with [dev.kuml.core.model.SequenceDiagramConfig].
 */
fun sequenceDiagram(
    name: String,
    block: SequenceDiagramBuilder.() -> Unit = {},
): KumlDiagram = SequenceDiagramBuilder(name = name).apply(block).build()

// ─────────────────────────────────────────────────────────────────────────────
// V1.1 — remaining UML 2.x diagram types (object diagram lives above next to
// classDiagram).
// ─────────────────────────────────────────────────────────────────────────────

/** Creates a UML 2.x package diagram (V1.1). */
public fun packageDiagram(
    name: String,
    block: dev.kuml.uml.dsl.PackageDiagramBuilder.() -> Unit = {},
): KumlDiagram =
    dev.kuml.uml.dsl
        .PackageDiagramBuilder(name = name)
        .apply(block)
        .build()

/** Creates a UML 2.x composite-structure diagram (V1.1). */
public fun compositeStructureDiagram(
    name: String,
    block: dev.kuml.uml.dsl.CompositeStructureDiagramBuilder.() -> Unit = {},
): KumlDiagram =
    dev.kuml.uml.dsl
        .CompositeStructureDiagramBuilder(name = name)
        .apply(block)
        .build()

/** Creates a UML 2.x deployment diagram (V1.1). */
public fun deploymentDiagram(
    name: String,
    block: dev.kuml.uml.dsl.DeploymentDiagramBuilder.() -> Unit = {},
): KumlDiagram =
    dev.kuml.uml.dsl
        .DeploymentDiagramBuilder(name = name)
        .apply(block)
        .build()

/** Creates a UML 2.x profile diagram (V1.1). */
public fun profileDiagram(
    name: String,
    block: dev.kuml.uml.dsl.ProfileDiagramBuilder.() -> Unit = {},
): KumlDiagram =
    dev.kuml.uml.dsl
        .ProfileDiagramBuilder(name = name)
        .apply(block)
        .build()

/** Creates a UML 2.x activity diagram (V1.1). */
public fun activityDiagram(
    name: String,
    block: dev.kuml.uml.dsl.ActivityDiagramBuilder.() -> Unit = {},
): KumlDiagram =
    dev.kuml.uml.dsl
        .ActivityDiagramBuilder(name = name)
        .apply(block)
        .build()

/** Creates a UML 2.x communication diagram (V1.1). */
public fun communicationDiagram(
    name: String,
    block: dev.kuml.uml.dsl.CommunicationDiagramBuilder.() -> Unit = {},
): KumlDiagram =
    dev.kuml.uml.dsl
        .CommunicationDiagramBuilder(name = name)
        .apply(block)
        .build()

/** Creates a UML 2.x timing diagram (V1.1). */
public fun timingDiagram(
    name: String,
    block: dev.kuml.uml.dsl.TimingDiagramBuilder.() -> Unit = {},
): KumlDiagram =
    dev.kuml.uml.dsl
        .TimingDiagramBuilder(name = name)
        .apply(block)
        .build()

/** Creates a UML 2.x interaction-overview diagram (V1.1). */
public fun interactionOverviewDiagram(
    name: String,
    block: dev.kuml.uml.dsl.InteractionOverviewDiagramBuilder.() -> Unit = {},
): KumlDiagram =
    dev.kuml.uml.dsl
        .InteractionOverviewDiagramBuilder(name = name)
        .apply(block)
        .build()
