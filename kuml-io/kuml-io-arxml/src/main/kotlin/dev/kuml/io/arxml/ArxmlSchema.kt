package dev.kuml.io.arxml

import org.jdom2.Namespace

/**
 * AUTOSAR ARXML namespace constants and element name constants.
 *
 * All Classic Platform R4.x releases share the same xmlns URI. Version distinction
 * requires inspecting the xsi:schemaLocation attribute — see [ArxmlVersion.detect].
 *
 * Element local-names are used WITHOUT an AR: prefix because JDOM2 resolves the
 * default namespace and child lookups use local-name + Namespace object.
 *
 * V3.1.33 — initial skeleton.
 */
public object ArxmlSchema {
    /** Shared xmlns URI for all AUTOSAR Classic R4.x schemas. */
    public const val AUTOSAR_NS_R40: String = "http://autosar.org/schema/r4.0"

    /** XMLSchema-instance namespace URI. */
    public const val XSI_NS: String = "http://www.w3.org/2001/XMLSchema-instance"

    // ── Element local-names ───────────────────────────────────────────────────
    public const val ELEM_AUTOSAR: String = "AUTOSAR"
    public const val ELEM_AR_PACKAGES: String = "AR-PACKAGES"
    public const val ELEM_AR_PACKAGE: String = "AR-PACKAGE"
    public const val ELEM_SHORT_NAME: String = "SHORT-NAME"
    public const val ELEM_ELEMENTS: String = "ELEMENTS"
    public const val ELEM_COMPOSITION_SWC: String = "COMPOSITION-SW-COMPONENT-TYPE"
    public const val ELEM_APPLICATION_SWC: String = "APPLICATION-SW-COMPONENT-TYPE"
    public const val ELEM_PORTS: String = "PORTS"
    public const val ELEM_P_PORT_PROTOTYPE: String = "P-PORT-PROTOTYPE"
    public const val ELEM_R_PORT_PROTOTYPE: String = "R-PORT-PROTOTYPE"
    public const val ELEM_SENDER_RECEIVER_INTERFACE: String = "SENDER-RECEIVER-INTERFACE"
    public const val ELEM_CLIENT_SERVER_INTERFACE: String = "CLIENT-SERVER-INTERFACE"
    public const val ELEM_INTERNAL_BEHAVIORS: String = "INTERNAL-BEHAVIORS"
    public const val ELEM_SWC_INTERNAL_BEHAVIOR: String = "SWC-INTERNAL-BEHAVIOR"
    public const val ELEM_RUNNABLES: String = "RUNNABLES"
    public const val ELEM_RUNNABLE_ENTITY: String = "RUNNABLE-ENTITY"

    // ── V3.1.34 — Runnable trigger event elements (under SWC-INTERNAL-BEHAVIOR/EVENTS) ──
    public const val ELEM_EVENTS: String = "EVENTS"
    public const val ELEM_TIMING_EVENT: String = "TIMING-EVENT"
    public const val ELEM_DATA_RECEIVED_EVENT: String = "DATA-RECEIVED-EVENT"
    public const val ELEM_OPERATION_INVOKED_EVENT: String = "OPERATION-INVOKED-EVENT"
    public const val ELEM_INIT_EVENT: String = "INIT-EVENT"
    public const val ELEM_SWC_MODE_SWITCH_EVENT: String = "SWC-MODE-SWITCH-EVENT"

    /** Path reference from an event element back to the RUNNABLE-ENTITY it activates. */
    public const val ELEM_START_ON_EVENT_REF: String = "START-ON-EVENT-REF"

    // ── V3.1.34 — Port interface cross-reference elements ────────────────────

    /** Required port → interface TREF element (child of R-PORT-PROTOTYPE). */
    public const val ELEM_REQUIRED_INTERFACE_TREF: String = "REQUIRED-INTERFACE-TREF"

    /** Provided port → interface TREF element (child of P-PORT-PROTOTYPE). */
    public const val ELEM_PROVIDED_INTERFACE_TREF: String = "PROVIDED-INTERFACE-TREF"

    // ── V3.1.34 — BehaviorSpec / state machine ───────────────────────────────

    /**
     * Represents an AUTOSAR behavior spec element carrying a state machine.
     * Written as `BEHAVIOR-SPEC` under SWC-INTERNAL-BEHAVIOR.
     */
    public const val ELEM_BEHAVIOR_SPEC: String = "BEHAVIOR-SPEC"

    /** DEST attribute value written on *-TREF elements to aid interoperability. */
    public const val ATTR_DEST: String = "DEST"

    // ── AUTOSAR profile stereotype names ─────────────────────────────────────
    // Single source of truth — ArxmlReader and ArxmlWriter reference these
    // constants so that a rename in the profile is caught at compile time.
    public const val STEREOTYPE_SOFTWARE_COMPONENT: String = "SoftwareComponent"
    public const val STEREOTYPE_COM_INTERFACE: String = "ComInterface"
    public const val STEREOTYPE_AUTOSAR_PORT: String = "AutosarPort"
    public const val STEREOTYPE_RUNNABLE: String = "Runnable"

    /** Stereotype applied to [dev.kuml.uml.UmlStateMachine] elements imported from BEHAVIOR-SPEC. */
    public const val STEREOTYPE_BEHAVIOR_SPEC: String = "BehaviorSpec"

    /**
     * Returns a JDOM2 [Namespace] for the given [ArxmlVersion].
     *
     * All R4.x versions share the same URI; this helper is provided for clarity
     * and future extensibility should the namespace ever change in a future AUTOSAR release.
     */
    public fun arNamespace(version: ArxmlVersion): Namespace = Namespace.getNamespace(version.namespaceUri)
}
