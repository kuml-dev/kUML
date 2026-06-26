package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.Namespace
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import java.io.File
import java.io.StringWriter

/**
 * Writes a kUML [UmlPackage] model as AUTOSAR Classic ARXML XML.
 *
 * The output conforms to the AUTOSAR 4.x schema structure:
 * ```
 * AUTOSAR
 *   AR-PACKAGES
 *     AR-PACKAGE
 *       SHORT-NAME
 *       ELEMENTS
 *         COMPOSITION-SW-COMPONENT-TYPE | APPLICATION-SW-COMPONENT-TYPE
 *           SHORT-NAME
 *           PORTS
 *             P-PORT-PROTOTYPE | R-PORT-PROTOTYPE
 *         SENDER-RECEIVER-INTERFACE | CLIENT-SERVER-INTERFACE
 * ```
 *
 * @property version  AUTOSAR schema version to use for the xmlns / xsi:schemaLocation output.
 *
 * V3.1.33 — initial implementation.
 */
public class ArxmlWriter(
    public val version: ArxmlVersion = ArxmlVersion.R22_11,
) {
    /**
     * Serialises [rootPackage] as pretty-printed AUTOSAR ARXML XML and returns the string.
     */
    public fun write(rootPackage: UmlPackage): String {
        val doc = buildDocument(rootPackage)
        val sw = StringWriter()
        XMLOutputter(Format.getPrettyFormat()).output(doc, sw)
        return sw.toString()
    }

    /**
     * Serialises [rootPackage] as AUTOSAR ARXML XML and writes it to [file].
     */
    public fun write(
        rootPackage: UmlPackage,
        file: File,
    ) {
        file.writeText(write(rootPackage))
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun buildDocument(rootPackage: UmlPackage): Document {
        val arNs = ArxmlSchema.arNamespace(version)
        val xsiNs = Namespace.getNamespace("xsi", ArxmlSchema.XSI_NS)

        val root = el(ArxmlSchema.ELEM_AUTOSAR, arNs)
        root.addNamespaceDeclaration(xsiNs)
        root.setAttribute(
            "schemaLocation",
            "${version.namespaceUri} ${version.schemaLabel}.xsd",
            xsiNs,
        )

        val arPackages = el(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
        root.addContent(arPackages)

        // Emit top-level packages from the rootPackage's members
        for (member in rootPackage.members) {
            if (member is UmlPackage) {
                arPackages.addContent(buildPackageElement(member, arNs))
            }
        }

        return Document(root)
    }

    private fun buildPackageElement(
        pkg: UmlPackage,
        arNs: Namespace,
    ): Element {
        val pkgEl = el(ArxmlSchema.ELEM_AR_PACKAGE, arNs)
        pkgEl.addContent(el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = pkg.name })

        val nestedPkgs = pkg.members.filterIsInstance<UmlPackage>()
        if (nestedPkgs.isNotEmpty()) {
            val nestedArPkgs = el(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
            for (nested in nestedPkgs) {
                nestedArPkgs.addContent(buildPackageElement(nested, arNs))
            }
            pkgEl.addContent(nestedArPkgs)
        }

        val elements = pkg.members.filter { it !is UmlPackage }
        if (elements.isNotEmpty()) {
            val elementsEl = el(ArxmlSchema.ELEM_ELEMENTS, arNs)
            for (member in elements) {
                val childEl = buildMemberElement(member, arNs)
                if (childEl != null) elementsEl.addContent(childEl)
            }
            pkgEl.addContent(elementsEl)
        }

        return pkgEl
    }

    private fun buildMemberElement(
        member: UmlNamedElement,
        arNs: Namespace,
    ): Element? =
        when (member) {
            is UmlComponent -> buildComponentElement(member, arNs)
            is UmlInterface -> buildInterfaceElement(member, arNs)
            else -> null
        }

    private fun buildComponentElement(
        component: UmlComponent,
        arNs: Namespace,
    ): Element {
        val kind = (component.metadata["kind"] as? KumlMetaValue.Text)?.value ?: "application"
        val tagName =
            if (kind == "composition") {
                ArxmlSchema.ELEM_COMPOSITION_SWC
            } else {
                ArxmlSchema.ELEM_APPLICATION_SWC
            }
        val compEl = el(tagName, arNs)
        compEl.addContent(el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = component.name })

        if (component.ports.isNotEmpty()) {
            val portsEl = el(ArxmlSchema.ELEM_PORTS, arNs)
            for (port in component.ports) {
                portsEl.addContent(buildPortElement(port, arNs))
            }
            compEl.addContent(portsEl)
        }

        return compEl
    }

    private fun buildPortElement(
        port: UmlPort,
        arNs: Namespace,
    ): Element {
        val direction = (port.metadata["direction"] as? KumlMetaValue.Text)?.value ?: "provided"
        val tagName =
            if (direction == "required") {
                ArxmlSchema.ELEM_R_PORT_PROTOTYPE
            } else {
                ArxmlSchema.ELEM_P_PORT_PROTOTYPE
            }
        val portEl = el(tagName, arNs)
        portEl.addContent(el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = port.name })
        return portEl
    }

    private fun buildInterfaceElement(
        iface: UmlInterface,
        arNs: Namespace,
    ): Element {
        val isService = (iface.metadata["isService"] as? KumlMetaValue.Text)?.value == "true"
        val tagName =
            if (isService) {
                ArxmlSchema.ELEM_CLIENT_SERVER_INTERFACE
            } else {
                ArxmlSchema.ELEM_SENDER_RECEIVER_INTERFACE
            }
        val ifaceEl = el(tagName, arNs)
        ifaceEl.addContent(el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = iface.name })
        return ifaceEl
    }

    private fun el(
        name: String,
        ns: Namespace,
    ): Element = Element(name, ns)
}
