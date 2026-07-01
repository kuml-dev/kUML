package dev.kuml.sysml2.dsl

/**
 * DSL marker for the SysML 2 builders. Prevents illegal scope nesting —
 * e.g. you can't reach the outer `sysml2Model { }` scope from inside an
 * inner `partDef { }` block by accident, the way Kotlin's normal lexical
 * scoping would otherwise allow.
 */
@DslMarker
annotation class Sysml2Dsl
