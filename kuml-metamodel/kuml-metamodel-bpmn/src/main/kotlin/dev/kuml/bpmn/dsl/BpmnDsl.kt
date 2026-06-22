package dev.kuml.bpmn.dsl

/**
 * DSL marker for the BPMN builders. Prevents illegal scope nesting —
 * e.g. you can't reach the outer `bpmnModel { }` scope from inside an
 * inner `process { }` block by accident, the way Kotlin's normal lexical
 * scoping would otherwise allow.
 */
@DslMarker
annotation class BpmnDsl
