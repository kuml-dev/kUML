package dev.kuml.kerml

import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlNamespaceMember

/**
 * Marker for every element in the KerML metamodel.
 *
 * KerML (the Kernel Modeling Language) is the OMG-standard semantic core that
 * SysML 2 builds on. The full KerML spec is ~80 metaclasses; this module
 * implements the **structural slice** that V2.0.3 needs to host the SysML 2
 * BDD MVP — namely:
 *
 *  - [KermlNamespace], [KermlNamespaceMember] — the "anything has a parent" graph
 *  - [KermlType], [KermlClassifier], [KermlDataType] — type hierarchy
 *  - [KermlFeature] — the unified concept that covers attributes, ports,
 *    parameters, ends, …
 *  - [KermlSpecialization] — `Type :> Type`, the inheritance relationship
 *
 * Everything else (behaviour, expressions, the `Conjugation` machinery,
 * derived features, the M2 reflection layer) is deferred to follow-up V2.x
 * waves. The MVP target is "enough to build a SysML 2 Part / Attribute / Port
 * tree and serialise it", not "kompletter OMG-Spec-Conformance".
 *
 * **Intentionally not sealed** — language-specific metamodels (SysML 2 in
 * `kuml-metamodel-sysml2`, future SysML profile add-ons) live in separate
 * modules and need to implement [KermlElement] from there. Kotlin's sealed
 * restriction requires subtypes to live in the same module, which is
 * incompatible with that layering. Sealed exhaustivity is provided one
 * level down — e.g. `sealed interface Sysml2Element : KermlElement` inside
 * the SysML 2 module. Same trick as [KumlElement] uses at the kUML core
 * level.
 */
interface KermlElement : KumlElement {
    override val metadata: Map<String, KumlMetaValue>
}

/**
 * A named, parented element — the `Element :> Membership :> Namespace` chain
 * collapsed to the form V2.0.3 actually consumes.
 *
 * `qualifiedName` is the dotted path from the containing namespace; SysML 2
 * tooling traditionally renders this as `Vehicle::engine::cylinders`. We use
 * `::` for parity with the spec — the serialised form is stable.
 */
interface KermlNamespaceMember :
    KermlElement,
    KumlNamespaceMember {
    override val name: String

    /**
     * Fully qualified name, e.g. `Vehicle::engine::cylinders`. May equal
     * [name] for top-level elements.
     */
    val qualifiedName: String
}
