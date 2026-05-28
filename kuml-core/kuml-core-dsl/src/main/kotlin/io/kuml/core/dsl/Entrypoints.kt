package io.kuml.core.dsl

import io.kuml.core.model.DiagramType
import io.kuml.core.model.KumlDiagram

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
