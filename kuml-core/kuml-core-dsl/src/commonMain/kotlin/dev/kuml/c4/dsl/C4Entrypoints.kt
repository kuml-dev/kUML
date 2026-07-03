package dev.kuml.c4.dsl

import dev.kuml.c4.model.C4Model

/**
 * Constructs a C4 model using a fluent DSL.
 *
 * Entry point for building C4 diagrams programmatically.
 *
 * Example:
 * ```kotlin
 * val model = c4Model("Internet Banking System") {
 *     val customer = person("Customer") {
 *         description = "A customer using the system"
 *     }
 *     val system = softwareSystem("Internet Banking") {
 *         description = "The main banking system"
 *         container("Web Application") {
 *             technology = "React"
 *         }
 *     }
 *     relationship(customer, system) {
 *         technology = "HTTPS"
 *     }
 * }
 * ```
 *
 * @param name The name of the C4 model
 * @param description Optional description of the model
 * @param block Configuration block to define model elements and relationships
 * @return The constructed immutable C4Model
 */
fun c4Model(
    name: String,
    description: String? = null,
    block: C4ModelBuilder.() -> Unit = {},
): C4Model {
    val builder = C4ModelBuilder(name)
    builder.apply(block)
    return builder.build()
}
