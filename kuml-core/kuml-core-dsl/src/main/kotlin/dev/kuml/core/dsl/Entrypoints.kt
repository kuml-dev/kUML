package dev.kuml.core.dsl

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.dsl.ClassDiagramBuilder

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
