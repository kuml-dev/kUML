package dev.kuml.blueprint.dsl

/**
 * DSL marker for the Blueprint builders. Prevents illegal scope nesting —
 * e.g. you can't reach the outer `blueprint { }` scope from inside an inner
 * `phase { }` block by accident.
 *
 * V3.1.22
 */
@DslMarker
annotation class BlueprintDsl
