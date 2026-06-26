package dev.kuml.io.arxml

import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.Namespace
import org.jdom2.input.SAXBuilder
import org.jdom2.input.sax.XMLReaders
import java.io.File
import java.io.StringReader
import java.util.UUID

/**
 * Reads AUTOSAR ARXML files and maps them to the kUML metamodel.
 *
 * Security: XXE-hardened via [secureSaxBuilder] — DOCTYPE declarations are
 * forbidden (disallow-doctype-decl=true), external general/parameter entities
 * are disabled, and external DTD loading is disabled. This simultaneously
 * prevents XXE and billion-laughs entity-expansion attacks.
 *
 * Memory: the full DOM is loaded (acceptable for typical ARXML models up to
 * tens of MB). For very large AUTOSAR deliveries (>100 MB), streaming SAX
 * would be required — noted as future work.
 *
 * Mapping:
 * - AUTOSAR/AR-PACKAGES/AR-PACKAGE → nested [UmlPackage]
 * - COMPOSITION-SW-COMPONENT-TYPE  → [UmlComponent] stereotype "SoftwareComponent", metadata kind="composition"
 * - APPLICATION-SW-COMPONENT-TYPE  → [UmlComponent] stereotype "SoftwareComponent", metadata kind="application"
 * - P-PORT-PROTOTYPE               → [UmlPort] stereotype "AutosarPort", metadata direction="provided"
 * - R-PORT-PROTOTYPE               → [UmlPort] stereotype "AutosarPort", metadata direction="required"
 * - SENDER-RECEIVER-INTERFACE      → [UmlInterface] stereotype "ComInterface"
 * - CLIENT-SERVER-INTERFACE        → [UmlInterface] stereotype "ComInterface", metadata isService="true"
 * - RUNNABLE-ENTITY                → [UmlOperation] stereotype "Runnable"
 *
 * @property version  When non-null, overrides auto-detection from the root element.
 *
 * V3.1.33 — initial implementation.
 */
public class ArxmlReader(
    public val version: ArxmlVersion? = null,
) {
    /** Parses an AUTOSAR ARXML [file] and returns an [ArxmlParseResult]. */
    public fun read(file: File): ArxmlParseResult {
        val doc = secureSaxBuilder().build(file)
        return buildModel(doc)
    }

    /**
     * Parses an AUTOSAR ARXML [xml] string and returns an [ArxmlParseResult].
     * No temp file is written; the string is parsed directly via [StringReader].
     */
    public fun readFromString(xml: String): ArxmlParseResult {
        val doc = secureSaxBuilder().build(StringReader(xml))
        return buildModel(doc)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Builds an XXE-hardened [SAXBuilder].
     *
     * The primary guard is `disallow-doctype-decl=true` which causes any DOCTYPE
     * declaration to throw immediately — this kills both XXE injection and
     * billion-laughs entity-expansion in one flag.
     */
    private fun secureSaxBuilder(): SAXBuilder {
        val sb = SAXBuilder(XMLReaders.NONVALIDATING)
        sb.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        sb.setFeature("http://xml.org/sax/features/external-general-entities", false)
        sb.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        sb.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        sb.expandEntities = false
        return sb
    }

    private fun buildModel(doc: Document): ArxmlParseResult {
        val root = doc.rootElement
        val warnings = mutableListOf<String>()

        // Detect or use configured version
        val detectedVersion =
            version ?: run {
                val detected = ArxmlVersion.detect(root)
                if (detected == null) {
                    warnings.add("Root element does not carry AUTOSAR R4.x namespace; defaulting to R22_11")
                    ArxmlVersion.R22_11
                } else if (detected.schemaLabel == ArxmlVersion.R19_11.schemaLabel &&
                    root.getAttributeValue(
                        "schemaLocation",
                        Namespace.getNamespace("xsi", ArxmlSchema.XSI_NS),
                    ) == null
                ) {
                    // fromNamespace fallback — schemaLocation was absent
                    warnings.add(
                        "xsi:schemaLocation absent — cannot determine exact AUTOSAR release; " +
                            "defaulting to ${ArxmlVersion.R22_11.name}",
                    )
                    ArxmlVersion.R22_11
                } else {
                    detected
                }
            }

        val arNs = ArxmlSchema.arNamespace(detectedVersion)

        // Navigate AUTOSAR → AR-PACKAGES
        val arPackages =
            root.getChild(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
                ?: root.getChild(ArxmlSchema.ELEM_AR_PACKAGES, Namespace.NO_NAMESPACE)

        val members = mutableListOf<UmlNamedElement>()
        if (arPackages != null) {
            for (
            pkgEl in arPackages
                .getChildren(ArxmlSchema.ELEM_AR_PACKAGE, arNs)
                .ifEmpty { arPackages.getChildren(ArxmlSchema.ELEM_AR_PACKAGE, Namespace.NO_NAMESPACE) }
            ) {
                members.add(parsePackage(pkgEl, arNs, warnings))
            }
        } else {
            warnings.add("No AR-PACKAGES element found in root AUTOSAR document")
        }

        val rootPackage =
            UmlPackage(
                id = UUID.randomUUID().toString(),
                name = "AUTOSAR",
                members = members,
            )
        return ArxmlParseResult(rootPackage = rootPackage, version = detectedVersion, warnings = warnings)
    }

    private fun parsePackage(
        pkgEl: Element,
        arNs: Namespace,
        warnings: MutableList<String>,
    ): UmlPackage {
        val shortName =
            pkgEl.getChildText(ArxmlSchema.ELEM_SHORT_NAME, arNs)
                ?: pkgEl.getChildText(ArxmlSchema.ELEM_SHORT_NAME, Namespace.NO_NAMESPACE)
                ?: "UnnamedPackage"

        val members = mutableListOf<UmlNamedElement>()

        // Nested AR-PACKAGES (sub-packages)
        val nestedArPackages =
            pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
                ?: pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, Namespace.NO_NAMESPACE)
        if (nestedArPackages != null) {
            for (
            subPkgEl in nestedArPackages
                .getChildren(ArxmlSchema.ELEM_AR_PACKAGE, arNs)
                .ifEmpty { nestedArPackages.getChildren(ArxmlSchema.ELEM_AR_PACKAGE, Namespace.NO_NAMESPACE) }
            ) {
                members.add(parsePackage(subPkgEl, arNs, warnings))
            }
        }

        // ELEMENTS block
        val elementsEl =
            pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, arNs)
                ?: pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, Namespace.NO_NAMESPACE)
        if (elementsEl != null) {
            for (child in elementsEl.children) {
                val localName = child.name
                when (localName) {
                    ArxmlSchema.ELEM_COMPOSITION_SWC ->
                        members.add(parseSoftwareComponent(child, arNs, "composition"))

                    ArxmlSchema.ELEM_APPLICATION_SWC ->
                        members.add(parseSoftwareComponent(child, arNs, "application"))

                    ArxmlSchema.ELEM_SENDER_RECEIVER_INTERFACE ->
                        members.add(parseInterface(child, arNs, isService = false))

                    ArxmlSchema.ELEM_CLIENT_SERVER_INTERFACE ->
                        members.add(parseInterface(child, arNs, isService = true))

                    ArxmlSchema.ELEM_SHORT_NAME -> { /* already handled above */ }

                    else ->
                        warnings.add(
                            "Unknown AUTOSAR element <$localName> in package '$shortName' — skipped",
                        )
                }
            }
        }

        return UmlPackage(
            id = UUID.randomUUID().toString(),
            name = shortName,
            members = members,
        )
    }

    private fun parseSoftwareComponent(
        el: Element,
        arNs: Namespace,
        kind: String,
    ): UmlComponent {
        val shortName =
            el.getChildText(ArxmlSchema.ELEM_SHORT_NAME, arNs)
                ?: el.getChildText(ArxmlSchema.ELEM_SHORT_NAME, Namespace.NO_NAMESPACE)
                ?: "UnnamedSWC"

        val ports = mutableListOf<UmlPort>()

        val portsEl =
            el.getChild(ArxmlSchema.ELEM_PORTS, arNs)
                ?: el.getChild(ArxmlSchema.ELEM_PORTS, Namespace.NO_NAMESPACE)
        if (portsEl != null) {
            for (portEl in portsEl.children) {
                when (portEl.name) {
                    ArxmlSchema.ELEM_P_PORT_PROTOTYPE ->
                        ports.add(parsePort(portEl, arNs, direction = "provided"))

                    ArxmlSchema.ELEM_R_PORT_PROTOTYPE ->
                        ports.add(parsePort(portEl, arNs, direction = "required"))
                }
            }
        }

        // INTERNAL-BEHAVIORS → runnables stored as operations
        val operations = mutableListOf<UmlOperation>()
        val behaviorsEl =
            el.getChild(ArxmlSchema.ELEM_INTERNAL_BEHAVIORS, arNs)
                ?: el.getChild(ArxmlSchema.ELEM_INTERNAL_BEHAVIORS, Namespace.NO_NAMESPACE)
        if (behaviorsEl != null) {
            for (behaviorEl in behaviorsEl.children) {
                for (
                runnableEl in behaviorEl
                    .getChildren(ArxmlSchema.ELEM_RUNNABLE_ENTITY, arNs)
                    .ifEmpty { behaviorEl.getChildren(ArxmlSchema.ELEM_RUNNABLE_ENTITY, Namespace.NO_NAMESPACE) }
                ) {
                    operations.add(parseRunnable(runnableEl, arNs))
                }
            }
        }

        return UmlComponent(
            id = UUID.randomUUID().toString(),
            name = shortName,
            ports = ports,
            operations = operations,
            stereotypes = listOf("SoftwareComponent"),
            metadata =
                mapOf(
                    "kind" to
                        dev.kuml.core.model.KumlMetaValue
                            .Text(kind),
                ),
        )
    }

    private fun parsePort(
        el: Element,
        arNs: Namespace,
        direction: String,
    ): UmlPort {
        val shortName =
            el.getChildText(ArxmlSchema.ELEM_SHORT_NAME, arNs)
                ?: el.getChildText(ArxmlSchema.ELEM_SHORT_NAME, Namespace.NO_NAMESPACE)
                ?: "UnnamedPort"
        return UmlPort(
            id = UUID.randomUUID().toString(),
            name = shortName,
            stereotypes = listOf("AutosarPort"),
            metadata =
                mapOf(
                    "direction" to
                        dev.kuml.core.model.KumlMetaValue
                            .Text(direction),
                ),
        )
    }

    private fun parseInterface(
        el: Element,
        arNs: Namespace,
        isService: Boolean,
    ): UmlInterface {
        val shortName =
            el.getChildText(ArxmlSchema.ELEM_SHORT_NAME, arNs)
                ?: el.getChildText(ArxmlSchema.ELEM_SHORT_NAME, Namespace.NO_NAMESPACE)
                ?: "UnnamedInterface"
        return UmlInterface(
            id = UUID.randomUUID().toString(),
            name = shortName,
            stereotypes = listOf("ComInterface"),
            metadata =
                if (isService) {
                    mapOf(
                        "isService" to
                            dev.kuml.core.model.KumlMetaValue
                                .Text("true"),
                    )
                } else {
                    emptyMap()
                },
        )
    }

    private fun parseRunnable(
        el: Element,
        arNs: Namespace,
    ): UmlOperation {
        val shortName =
            el.getChildText(ArxmlSchema.ELEM_SHORT_NAME, arNs)
                ?: el.getChildText(ArxmlSchema.ELEM_SHORT_NAME, Namespace.NO_NAMESPACE)
                ?: "UnnamedRunnable"
        return UmlOperation(
            id = UUID.randomUUID().toString(),
            name = shortName,
            stereotypes = listOf("Runnable"),
        )
    }
}
