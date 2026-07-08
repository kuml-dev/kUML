package dev.kuml.erm.dsl

/**
 * DSL marker for the ERM builders. Prevents illegal scope nesting — e.g. you
 * can't reach the outer `ermModel { }` scope from inside an `entity { }`
 * block by accident.
 *
 * V3.4.1
 */
@DslMarker
annotation class ErmDsl
