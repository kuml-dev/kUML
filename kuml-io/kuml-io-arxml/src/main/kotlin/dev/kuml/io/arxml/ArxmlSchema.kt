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
    public const val ELEM_RUNNABLE_ENTITY: String = "RUNNABLE-ENTITY"

    /**
     * Returns a JDOM2 [Namespace] for the given [ArxmlVersion].
     *
     * All R4.x versions share the same URI; this helper is provided for clarity
     * and future extensibility should the namespace ever change in a future AUTOSAR release.
     */
    public fun arNamespace(version: ArxmlVersion): Namespace = Namespace.getNamespace(version.namespaceUri)
}
