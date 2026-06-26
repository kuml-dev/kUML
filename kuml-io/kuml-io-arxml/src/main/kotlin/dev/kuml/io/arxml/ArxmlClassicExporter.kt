package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlOperation
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
 * Exports a [KumlModel] (with AUTOSAR profile stereotypes) as AUTOSAR Classic ARXML XML.
 *
 * Traverses the [KumlModel.root] element (expected to be a [UmlPackage]) and emits the
 * AUTOSAR element tree including:
 * - `AR-PACKAGE` nesting from [UmlPackage] hierarchy
 * - `COMPOSITION-SW-COMPONENT-TYPE` / `APPLICATION-SW-COMPONENT-TYPE` from [UmlComponent]
 * - `P-PORT-PROTOTYPE` / `R-PORT-PROTOTYPE` from [UmlPort] with direction metadata
 * - `PROVIDED-INTERFACE-TREF` / `REQUIRED-INTERFACE-TREF` resolved from port `interfaceRef` metadata
 * - `SENDER-RECEIVER-INTERFACE` / `CLIENT-SERVER-INTERFACE` from [UmlInterface]
 * - `SWC-INTERNAL-BEHAVIOR` with `RUNNABLES` and `EVENTS` blocks from [UmlOperation] with "Runnable" stereotype
 * - `BEHAVIOR-SPEC` from component `behaviorSpec` metadata
 *
 * When [emitAdaptiveManifests] is `true`, additional Adaptive Platform manifest elements are emitted
 * for components/packages that carry Adaptive metadata:
 * - `SERVICE-INSTANCE` for elements with `kind=ServiceInstance` metadata
 * - `ADAPTIVE-APPLICATION-SW-COMPONENT-TYPE` for elements with `kind=AdaptiveApplication` metadata
 * - `MACHINE-DESIGN` for elements with `kind=Machine` metadata
 * - `SERVICE-MANIFEST` / `MACHINE-MANIFEST` for elements with `kind=Manifest` metadata
 *
 * When [emitAdaptiveManifests] is `false` (the default), the Classic output is byte-identical to V3.1.34
 * — no regression risk for existing tests.
 *
 * **DEST attribute**: `*-TREF` elements carry a `DEST` attribute for AUTOSAR interoperability
 * (e.g. `DEST="SENDER-RECEIVER-INTERFACE"`). The importer is lenient and ignores this attribute;
 * the exporter emits it based on the interface kind stored in the model.
 *
 * @property version AUTOSAR schema version used for xmlns / xsi:schemaLocation output.
 * @property emitAdaptiveManifests When `true`, Adaptive Platform manifest elements are emitted.
 *
 * V3.1.34 — initial implementation.
 * V3.1.35 — added [emitAdaptiveManifests] flag.
 */
public class ArxmlClassicExporter(
    public val version: ArxmlVersion = ArxmlVersion.R22_11,
    public val emitAdaptiveManifests: Boolean = false,
) {
    /**
     * Exports [model] as AUTOSAR ARXML XML and returns the pretty-printed string.
     */
    public fun export(model: KumlModel): String {
        val rootPackage = requireRootPackage(model)
        // Build a path index: element name → absolute AUTOSAR path (for TREF emission)
        val pathIndex = buildPathIndex(rootPackage, "/")
        // Build a dest index: absolute interface path → ARXML element name (for DEST attribute).
        // The synthetic root UmlPackage ("AUTOSAR") does not appear in emitted AR-PACKAGES, so we
        // index the root's direct children starting from "/" — matching the paths stored by the
        // importer which also starts indexing from "/" below the AR-PACKAGES root element.
        val interfaceDestIndex = mutableMapOf<String, String>()
        for (child in rootPackage.members.filterIsInstance<UmlPackage>()) {
            interfaceDestIndex.putAll(buildInterfaceDestIndex(child, "/"))
        }
        val doc = buildDocument(rootPackage, pathIndex, interfaceDestIndex)
        val sw = StringWriter()
        XMLOutputter(Format.getPrettyFormat()).output(doc, sw)
        return sw.toString()
    }

    /**
     * Exports [model] as AUTOSAR ARXML XML and writes it to [file].
     */
    public fun export(
        model: KumlModel,
        file: File,
    ) {
        file.writeText(export(model))
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun requireRootPackage(model: KumlModel): UmlPackage =
        model.root as? UmlPackage
            ?: throw IllegalArgumentException(
                "ArxmlClassicExporter requires model.root to be a UmlPackage; got ${model.root::class.simpleName}",
            )

    /**
     * Builds a map from **absolute AUTOSAR path** to itself (for existence checks).
     *
     * Keys are absolute paths (e.g. `/Interfaces/IBrake`), which matches the `interfaceRef`
     * metadata stored on ports by the importer. Keying by short-name would cause silent
     * collisions when two interfaces in different packages share the same name — the second
     * would overwrite the first, producing invalid cross-references without any warning.
     */
    private fun buildPathIndex(
        pkg: UmlPackage,
        parentPath: String,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pkgPath = ArxmlPath.append(parentPath, pkg.name)
        result[pkgPath] = pkgPath
        for (member in pkg.members) {
            when (member) {
                is UmlPackage -> result.putAll(buildPathIndex(member, pkgPath))
                is UmlComponent -> {
                    val path = ArxmlPath.append(pkgPath, member.name)
                    result[path] = path
                }
                is UmlInterface -> {
                    val path = ArxmlPath.append(pkgPath, member.name)
                    result[path] = path
                }
                else -> { /* other element types: skip */ }
            }
        }
        return result
    }

    /**
     * Builds a map from the absolute AUTOSAR path of each [UmlInterface] to its ARXML element
     * name (e.g. `"SENDER-RECEIVER-INTERFACE"` or `"CLIENT-SERVER-INTERFACE"`).
     * Used to emit the correct `DEST` attribute on `*-INTERFACE-TREF` elements.
     */
    private fun buildInterfaceDestIndex(
        pkg: UmlPackage,
        parentPath: String,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pkgPath = ArxmlPath.append(parentPath, pkg.name)
        for (member in pkg.members) {
            when (member) {
                is UmlPackage -> result.putAll(buildInterfaceDestIndex(member, pkgPath))
                is UmlInterface -> {
                    val path = ArxmlPath.append(pkgPath, member.name)
                    val isService = (member.metadata["isService"] as? KumlMetaValue.Text)?.value == "true"
                    result[path] =
                        if (isService) {
                            ArxmlSchema.ELEM_CLIENT_SERVER_INTERFACE
                        } else {
                            ArxmlSchema.ELEM_SENDER_RECEIVER_INTERFACE
                        }
                }
                else -> { /* other element types: skip */ }
            }
        }
        return result
    }

    private fun buildDocument(
        rootPackage: UmlPackage,
        pathIndex: Map<String, String>,
        interfaceDestIndex: Map<String, String>,
    ): Document {
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

        for (member in rootPackage.members) {
            if (member is UmlPackage) {
                arPackages.addContent(buildPackageElement(member, arNs, pathIndex, interfaceDestIndex, "/"))
            }
        }

        return Document(root)
    }

    private fun buildPackageElement(
        pkg: UmlPackage,
        arNs: Namespace,
        pathIndex: Map<String, String>,
        interfaceDestIndex: Map<String, String>,
        parentPath: String,
    ): Element {
        val pkgPath = ArxmlPath.append(parentPath, pkg.name)
        val pkgEl = el(ArxmlSchema.ELEM_AR_PACKAGE, arNs)
        pkgEl.addContent(el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = pkg.name })

        val nestedPkgs = pkg.members.filterIsInstance<UmlPackage>()
        if (nestedPkgs.isNotEmpty()) {
            val nestedArPkgs = el(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
            for (nested in nestedPkgs) {
                nestedArPkgs.addContent(buildPackageElement(nested, arNs, pathIndex, interfaceDestIndex, pkgPath))
            }
            pkgEl.addContent(nestedArPkgs)
        }

        val nonPkgMembers = pkg.members.filter { it !is UmlPackage }
        if (nonPkgMembers.isNotEmpty()) {
            val elementsEl = el(ArxmlSchema.ELEM_ELEMENTS, arNs)
            for (member in nonPkgMembers) {
                val childEl = buildMemberElement(member, arNs, pathIndex, interfaceDestIndex, pkgPath)
                if (childEl != null) elementsEl.addContent(childEl)
            }
            pkgEl.addContent(elementsEl)
        }

        return pkgEl
    }

    private fun buildMemberElement(
        member: UmlNamedElement,
        arNs: Namespace,
        pathIndex: Map<String, String>,
        interfaceDestIndex: Map<String, String>,
        pkgPath: String,
    ): Element? =
        when (member) {
            is UmlComponent ->
                if (emitAdaptiveManifests) {
                    buildAdaptiveOrClassicComponentElement(member, arNs, pathIndex, interfaceDestIndex, pkgPath)
                } else {
                    buildComponentElement(member, arNs, pathIndex, interfaceDestIndex, pkgPath)
                }
            is UmlInterface -> buildInterfaceElement(member, arNs)
            else -> null
        }

    /**
     * When [emitAdaptiveManifests] is `true`, dispatches to an Adaptive element emitter
     * based on the component's `kind` metadata. Classic components fall through to the
     * standard [buildComponentElement].
     */
    private fun buildAdaptiveOrClassicComponentElement(
        component: UmlComponent,
        arNs: Namespace,
        pathIndex: Map<String, String>,
        interfaceDestIndex: Map<String, String>,
        pkgPath: String,
    ): Element {
        val kind = (component.metadata["kind"] as? KumlMetaValue.Text)?.value ?: ""
        return when (kind) {
            ArxmlSchema.STEREOTYPE_SERVICE_INSTANCE ->
                buildAdaptiveManifestElement(component, arNs, ArxmlSchema.ELEM_SERVICE_INSTANCE)
            ArxmlSchema.STEREOTYPE_ADAPTIVE_APPLICATION ->
                buildAdaptiveManifestElement(component, arNs, ArxmlSchema.ELEM_ADAPTIVE_APPLICATION_SWC)
            ArxmlSchema.STEREOTYPE_MACHINE ->
                buildAdaptiveManifestElement(component, arNs, ArxmlSchema.ELEM_MACHINE_DESIGN)
            ArxmlSchema.STEREOTYPE_MANIFEST -> {
                // manifestKind metadata determines the concrete element: "service" → SERVICE-MANIFEST,
                // "machine" → MACHINE-MANIFEST, default → SERVICE-MANIFEST.
                val manifestKind = (component.metadata["manifestKind"] as? KumlMetaValue.Text)?.value ?: "service"
                val tagName =
                    if (manifestKind == "machine") ArxmlSchema.ELEM_MACHINE_MANIFEST else ArxmlSchema.ELEM_SERVICE_MANIFEST
                buildAdaptiveManifestElement(component, arNs, tagName)
            }
            else ->
                buildComponentElement(component, arNs, pathIndex, interfaceDestIndex, pkgPath)
        }
    }

    /**
     * Emits a simple Adaptive Platform element (SERVICE-INSTANCE, ADAPTIVE-APPLICATION-SW-COMPONENT-TYPE,
     * or MACHINE-DESIGN) carrying only a SHORT-NAME child.
     *
     * Manifest elements (`kind=Manifest`) are handled via an additional metadata entry
     * `manifestKind` on components that carry `kind=Manifest`.
     */
    private fun buildAdaptiveManifestElement(
        component: UmlComponent,
        arNs: Namespace,
        tagName: String,
    ): Element {
        val adaptiveEl = el(tagName, arNs)
        adaptiveEl.addContent(
            el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = component.name },
        )
        return adaptiveEl
    }

    private fun buildComponentElement(
        component: UmlComponent,
        arNs: Namespace,
        pathIndex: Map<String, String>,
        interfaceDestIndex: Map<String, String>,
        pkgPath: String,
    ): Element {
        val compPath = ArxmlPath.append(pkgPath, component.name)
        val kind = (component.metadata["kind"] as? KumlMetaValue.Text)?.value ?: "application"
        val tagName = if (kind == "composition") ArxmlSchema.ELEM_COMPOSITION_SWC else ArxmlSchema.ELEM_APPLICATION_SWC
        val compEl = el(tagName, arNs)
        compEl.addContent(el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = component.name })

        if (component.ports.isNotEmpty()) {
            val portsEl = el(ArxmlSchema.ELEM_PORTS, arNs)
            for (port in component.ports) {
                portsEl.addContent(buildPortElement(port, arNs, interfaceDestIndex))
            }
            compEl.addContent(portsEl)
        }

        val runnables = component.operations.filter { ArxmlSchema.STEREOTYPE_RUNNABLE in it.stereotypes }
        val behaviorSpecName = (component.metadata["behaviorSpec"] as? KumlMetaValue.Text)?.value
        if (runnables.isNotEmpty() || behaviorSpecName != null) {
            val behaviorsEl = el(ArxmlSchema.ELEM_INTERNAL_BEHAVIORS, arNs)
            val behaviorEl = el(ArxmlSchema.ELEM_SWC_INTERNAL_BEHAVIOR, arNs)
            // Use the original internal behavior name stored during import (preserves vendor names).
            // Falls back to the synthesised "${compName}_InternalBehavior" for models built in-memory.
            val behaviorName =
                (component.metadata["internalBehaviorName"] as? KumlMetaValue.Text)?.value
                    ?: "${component.name}_InternalBehavior"
            behaviorEl.addContent(
                el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also {
                    it.text = behaviorName
                },
            )

            // RUNNABLES block
            val runnablesEl = el(ArxmlSchema.ELEM_RUNNABLES, arNs)
            for (runnable in runnables) {
                runnablesEl.addContent(buildRunnableElement(runnable, arNs))
            }
            behaviorEl.addContent(runnablesEl)

            // EVENTS block — one event per runnable that has a trigger
            val eventsWithTrigger =
                runnables.filter {
                    (it.metadata["trigger"] as? KumlMetaValue.Text) != null
                }
            if (eventsWithTrigger.isNotEmpty()) {
                val behaviorPath = ArxmlPath.append(compPath, behaviorName)
                val eventsEl = el(ArxmlSchema.ELEM_EVENTS, arNs)
                for (runnable in eventsWithTrigger) {
                    val triggerName = (runnable.metadata["trigger"] as KumlMetaValue.Text).value
                    val trigger = runTriggerByName(triggerName)
                    val eventEl = el(trigger.arxmlElementName, arNs)
                    eventEl.addContent(
                        el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also {
                            it.text = "${runnable.name}_${trigger.name}_Event"
                        },
                    )
                    // START-ON-EVENT-REF: full AUTOSAR absolute path to the RUNNABLE-ENTITY
                    val runnableAbsPath = ArxmlPath.append(behaviorPath, runnable.name)
                    val startOnEventRefEl = el(ArxmlSchema.ELEM_START_ON_EVENT_REF, arNs)
                    startOnEventRefEl.text = runnableAbsPath
                    startOnEventRefEl.setAttribute(ArxmlSchema.ATTR_DEST, ArxmlSchema.ELEM_RUNNABLE_ENTITY)
                    eventEl.addContent(startOnEventRefEl)
                    eventsEl.addContent(eventEl)
                }
                behaviorEl.addContent(eventsEl)
            }

            // BEHAVIOR-SPEC from component metadata
            if (behaviorSpecName != null) {
                val behaviorSpecEl = el(ArxmlSchema.ELEM_BEHAVIOR_SPEC, arNs)
                behaviorSpecEl.addContent(
                    el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = behaviorSpecName },
                )
                behaviorEl.addContent(behaviorSpecEl)
            }

            behaviorsEl.addContent(behaviorEl)
            compEl.addContent(behaviorsEl)
        }

        return compEl
    }

    private fun buildRunnableElement(
        operation: UmlOperation,
        arNs: Namespace,
    ): Element {
        val runnableEl = el(ArxmlSchema.ELEM_RUNNABLE_ENTITY, arNs)
        runnableEl.addContent(el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = operation.name })
        return runnableEl
    }

    private fun buildPortElement(
        port: UmlPort,
        arNs: Namespace,
        interfaceDestIndex: Map<String, String>,
    ): Element {
        val direction = (port.metadata["direction"] as? KumlMetaValue.Text)?.value ?: "provided"
        val tagName = if (direction == "required") ArxmlSchema.ELEM_R_PORT_PROTOTYPE else ArxmlSchema.ELEM_P_PORT_PROTOTYPE
        val portEl = el(tagName, arNs)
        portEl.addContent(el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = port.name })

        // Emit REQUIRED/PROVIDED-INTERFACE-TREF if interfaceRef metadata is present
        val interfaceRefPath = (port.metadata["interfaceRef"] as? KumlMetaValue.Text)?.value
        if (interfaceRefPath != null) {
            val trefElemName =
                if (direction == "provided") {
                    ArxmlSchema.ELEM_PROVIDED_INTERFACE_TREF
                } else {
                    ArxmlSchema.ELEM_REQUIRED_INTERFACE_TREF
                }
            // Determine DEST attribute value by looking up the target interface in the index.
            // Falls back to SENDER-RECEIVER-INTERFACE when the path is not found (e.g. external ref).
            val destValue =
                interfaceDestIndex[interfaceRefPath]
                    ?: ArxmlSchema.ELEM_SENDER_RECEIVER_INTERFACE
            val trefEl = el(trefElemName, arNs)
            trefEl.text = interfaceRefPath
            trefEl.setAttribute(ArxmlSchema.ATTR_DEST, destValue)
            portEl.addContent(trefEl)
        }

        return portEl
    }

    private fun buildInterfaceElement(
        iface: UmlInterface,
        arNs: Namespace,
    ): Element {
        val isService = (iface.metadata["isService"] as? KumlMetaValue.Text)?.value == "true"
        val tagName = if (isService) ArxmlSchema.ELEM_CLIENT_SERVER_INTERFACE else ArxmlSchema.ELEM_SENDER_RECEIVER_INTERFACE
        val ifaceEl = el(tagName, arNs)
        ifaceEl.addContent(el(ArxmlSchema.ELEM_SHORT_NAME, arNs).also { it.text = iface.name })
        return ifaceEl
    }

    private fun runTriggerByName(name: String): RunnableTrigger =
        RunnableTrigger.entries.firstOrNull { it.name == name } ?: RunnableTrigger.TIMING

    private fun el(
        name: String,
        ns: Namespace,
    ): Element = Element(name, ns)
}
