package dev.kuml.transform.bpmnuml

import dev.kuml.codegen.m2m.TransformerRegistry

/**
 * Convenience registry façade for the BPMN⇌UML Activity bridge transformers.
 *
 * [registerAll] performs programmatic registration — useful in tests and
 * embedded usage where ServiceLoader-based auto-discovery via
 * [TransformerRegistry.loadFromClasspath] is not desired.
 *
 * Mirrors the pattern established by `BpmnUmlBridgeRegistry` in the AUTOSAR
 * profile and `TransformerRegistry` in the M2M core module.
 */
public object BpmnUmlBridgeRegistry {
    /**
     * Registers both BPMN⇌UML Activity bridge transformers in the global
     * [TransformerRegistry].
     *
     * Idempotent: calling [registerAll] more than once simply overwrites the
     * previous registration with an identical instance (the registry uses a
     * mutable map keyed by transformer id).
     */
    public fun registerAll() {
        TransformerRegistry.register(BpmnToUmlActivityTransformer())
        TransformerRegistry.register(UmlActivityToBpmnTransformer())
    }
}
