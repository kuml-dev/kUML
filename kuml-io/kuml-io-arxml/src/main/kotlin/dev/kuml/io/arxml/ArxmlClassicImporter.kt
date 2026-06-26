package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlPort
import org.jdom2.Element
import org.jdom2.Namespace
import java.io.File
import java.io.StringReader
import java.util.UUID

/**
 * Imports an AUTOSAR Classic ARXML file and produces a [ImportResult] containing a [KumlModel].
 *
 * **Two-pass algorithm**:
 * - Pass 1: walks the entire `AUTOSAR/AR-PACKAGES` tree and indexes every SHORT-NAME-bearing
 *   element by its **absolute AUTOSAR path** (`/Package/SubPkg/ShortName`) into a
 *   `Map<String, Element>`.
 * - Pass 2: builds the kUML element tree and resolves cross-references (port interface TREFs,
 *   runnable trigger events) against the pass-1 index. Dangling references are recorded in
 *   [ImportResult.unresolved] rather than throwing.
 *
 * **Contract**: never throws for partial/incomplete ARXML input. Missing optional elements
 * produce [ImportResult.warnings]; dangling TREFs produce [ImportResult.unresolved].
 * Only structurally invalid XML (malformed markup, DOCTYPE injection) causes an exception
 * from the underlying XXE-hardened JDOM2 parser.
 *
 * Mapping:
 * - `COMPOSITION-SW-COMPONENT-TYPE` → [UmlComponent] + stereotype "SoftwareComponent", metadata `kind=composition`
 * - `APPLICATION-SW-COMPONENT-TYPE` → [UmlComponent] + stereotype "SoftwareComponent", metadata `kind=application`
 * - `P-PORT-PROTOTYPE`              → [UmlPort] + stereotype "AutosarPort", metadata `direction=provided`; resolves `PROVIDED-INTERFACE-TREF`
 * - `R-PORT-PROTOTYPE`              → [UmlPort] + stereotype "AutosarPort", metadata `direction=required`; resolves `REQUIRED-INTERFACE-TREF`
 * - `SENDER-RECEIVER-INTERFACE`     → [UmlInterface] + stereotype "ComInterface"
 * - `CLIENT-SERVER-INTERFACE`       → [UmlInterface] + stereotype "ComInterface", metadata `isService=true`
 * - `RUNNABLE-ENTITY`               → [UmlOperation] + stereotype "Runnable"; trigger joined from EVENTS block
 * - `BEHAVIOR-SPEC`                 → stored as component metadata `behaviorSpec` (SHORT-NAME value); no separate model element is created
 * - `SWC-INTERNAL-BEHAVIOR SHORT-NAME` → stored as component metadata `internalBehaviorName` to preserve the original name across export→re-import roundtrips
 *
 * @property version When non-null, overrides auto-detection from the root element.
 *
 * V3.1.34 — initial implementation.
 */
public class ArxmlClassicImporter(
    public val version: ArxmlVersion? = null,
) {
    /**
     * Parses [file] and returns an [ImportResult] containing the full [KumlModel].
     */
    public fun import(file: File): ImportResult {
        val doc = ArxmlSax.secureBuilder().build(file)
        return buildResult(doc.rootElement)
    }

    /**
     * Parses the ARXML [xml] string and returns an [ImportResult] containing the full [KumlModel].
     * No temp file is written; the string is parsed directly.
     */
    public fun importFromString(xml: String): ImportResult {
        val doc = ArxmlSax.secureBuilder().build(StringReader(xml))
        return buildResult(doc.rootElement)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun buildResult(root: Element): ImportResult {
        val warnings = mutableListOf<String>()
        val unresolved = mutableListOf<ImportResult.UnresolvedRef>()

        // Detect AUTOSAR version
        val detectedVersion = detectVersion(root, warnings)
        val arNs = ArxmlSchema.arNamespace(detectedVersion)

        // ── PASS 1: build path index ──────────────────────────────────────────
        val pathIndex = mutableMapOf<String, Element>()
        val arPackagesRoot =
            root.getChild(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
                ?: root.getChild(ArxmlSchema.ELEM_AR_PACKAGES, Namespace.NO_NAMESPACE)

        if (arPackagesRoot != null) {
            indexPackages(arPackagesRoot, arNs, "/", pathIndex)
        } else {
            warnings.add("No AR-PACKAGES element found in root AUTOSAR document")
        }

        // ── PASS 2: build UML tree with cross-ref resolution ─────────────────
        val members = mutableListOf<UmlNamedElement>()
        if (arPackagesRoot != null) {
            for (pkgEl in arPackagesRoot.getChildrenDual(ArxmlSchema.ELEM_AR_PACKAGE, arNs)) {
                members.add(buildPackage(pkgEl, arNs, "/", pathIndex, warnings, unresolved))
            }
        }

        val rootPackage =
            UmlPackage(
                id = UUID.randomUUID().toString(),
                name = "AUTOSAR",
                members = members,
            )
        val model =
            KumlModel(
                root = rootPackage,
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "AUTOSAR Import",
            )
        return ImportResult(model = model, warnings = warnings, unresolved = unresolved)
    }

    // ── Pass 1: path indexing ─────────────────────────────────────────────────

    private fun indexPackages(
        arPackagesEl: Element,
        arNs: Namespace,
        parentPath: String,
        index: MutableMap<String, Element>,
    ) {
        for (pkgEl in arPackagesEl.getChildrenDual(ArxmlSchema.ELEM_AR_PACKAGE, arNs)) {
            val shortName = pkgEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: continue
            val pkgPath = ArxmlPath.append(parentPath, shortName)
            index[pkgPath] = pkgEl

            // Index ELEMENTS members
            val elementsEl =
                pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, arNs)
                    ?: pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, Namespace.NO_NAMESPACE)
            if (elementsEl != null) {
                for (child in elementsEl.children) {
                    val childShortName = child.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: continue
                    val childPath = ArxmlPath.append(pkgPath, childShortName)
                    index[childPath] = child

                    // Index ports
                    val portsEl =
                        child.getChild(ArxmlSchema.ELEM_PORTS, arNs)
                            ?: child.getChild(ArxmlSchema.ELEM_PORTS, Namespace.NO_NAMESPACE)
                    if (portsEl != null) {
                        for (portEl in portsEl.children) {
                            val portName = portEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: continue
                            index[ArxmlPath.append(childPath, portName)] = portEl
                        }
                    }

                    // Index runnables under INTERNAL-BEHAVIORS/SWC-INTERNAL-BEHAVIOR/RUNNABLES
                    val behaviorsEl =
                        child.getChild(ArxmlSchema.ELEM_INTERNAL_BEHAVIORS, arNs)
                            ?: child.getChild(ArxmlSchema.ELEM_INTERNAL_BEHAVIORS, Namespace.NO_NAMESPACE)
                    if (behaviorsEl != null) {
                        for (behaviorEl in behaviorsEl.children) {
                            val behaviorName = behaviorEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: continue
                            val behaviorPath = ArxmlPath.append(childPath, behaviorName)
                            index[behaviorPath] = behaviorEl

                            val runnablesContainer =
                                behaviorEl.getChild(ArxmlSchema.ELEM_RUNNABLES, arNs)
                                    ?: behaviorEl.getChild(ArxmlSchema.ELEM_RUNNABLES, Namespace.NO_NAMESPACE)
                            if (runnablesContainer != null) {
                                for (runnableEl in runnablesContainer.getChildrenDual(ArxmlSchema.ELEM_RUNNABLE_ENTITY, arNs)) {
                                    val runnableName = runnableEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: continue
                                    index[ArxmlPath.append(behaviorPath, runnableName)] = runnableEl
                                }
                            }
                        }
                    }
                }
            }

            // Recurse into sub-packages
            val nestedArPkgs =
                pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
                    ?: pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, Namespace.NO_NAMESPACE)
            if (nestedArPkgs != null) {
                indexPackages(nestedArPkgs, arNs, pkgPath, index)
            }
        }
    }

    // ── Pass 2: UML tree building ─────────────────────────────────────────────

    private fun buildPackage(
        pkgEl: Element,
        arNs: Namespace,
        parentPath: String,
        pathIndex: Map<String, Element>,
        warnings: MutableList<String>,
        unresolved: MutableList<ImportResult.UnresolvedRef>,
    ): UmlPackage {
        val shortName = pkgEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: "UnnamedPackage"
        val pkgPath = ArxmlPath.append(parentPath, shortName)

        val members = mutableListOf<UmlNamedElement>()

        // Nested AR-PACKAGES
        val nestedArPkgs =
            pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
                ?: pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, Namespace.NO_NAMESPACE)
        if (nestedArPkgs != null) {
            for (subPkgEl in nestedArPkgs.getChildrenDual(ArxmlSchema.ELEM_AR_PACKAGE, arNs)) {
                members.add(buildPackage(subPkgEl, arNs, pkgPath, pathIndex, warnings, unresolved))
            }
        }

        // ELEMENTS
        val elementsEl =
            pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, arNs)
                ?: pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, Namespace.NO_NAMESPACE)
        if (elementsEl != null) {
            for (child in elementsEl.children) {
                val localName = child.name
                when (localName) {
                    ArxmlSchema.ELEM_COMPOSITION_SWC ->
                        members.add(buildSoftwareComponent(child, arNs, "composition", pkgPath, pathIndex, warnings, unresolved))

                    ArxmlSchema.ELEM_APPLICATION_SWC ->
                        members.add(buildSoftwareComponent(child, arNs, "application", pkgPath, pathIndex, warnings, unresolved))

                    ArxmlSchema.ELEM_SENDER_RECEIVER_INTERFACE ->
                        members.add(buildInterface(child, arNs, isService = false))

                    ArxmlSchema.ELEM_CLIENT_SERVER_INTERFACE ->
                        members.add(buildInterface(child, arNs, isService = true))

                    ArxmlSchema.ELEM_SHORT_NAME -> { /* already handled */ }

                    else ->
                        warnings.add("Unknown AUTOSAR element <$localName> in package '$shortName' — skipped")
                }
            }
        }

        return UmlPackage(
            id = UUID.randomUUID().toString(),
            name = shortName,
            members = members,
        )
    }

    @Suppress("LongParameterList")
    private fun buildSoftwareComponent(
        el: Element,
        arNs: Namespace,
        kind: String,
        pkgPath: String,
        pathIndex: Map<String, Element>,
        warnings: MutableList<String>,
        unresolved: MutableList<ImportResult.UnresolvedRef>,
    ): UmlComponent {
        val shortName = el.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: "UnnamedSWC"
        val swcPath = ArxmlPath.append(pkgPath, shortName)

        // Build ports — resolve interface TREFs
        val ports = mutableListOf<UmlPort>()
        val portsEl =
            el.getChild(ArxmlSchema.ELEM_PORTS, arNs)
                ?: el.getChild(ArxmlSchema.ELEM_PORTS, Namespace.NO_NAMESPACE)
        if (portsEl != null) {
            for (portEl in portsEl.children) {
                when (portEl.name) {
                    ArxmlSchema.ELEM_P_PORT_PROTOTYPE ->
                        ports.add(buildPort(portEl, arNs, "provided", swcPath, pathIndex, unresolved))

                    ArxmlSchema.ELEM_R_PORT_PROTOTYPE ->
                        ports.add(buildPort(portEl, arNs, "required", swcPath, pathIndex, unresolved))
                }
            }
        }

        // Build runnables — join with trigger events from EVENTS block
        val operations = mutableListOf<UmlOperation>()
        // BehaviorSpec is represented as component metadata ("behaviorSpec" → SHORT-NAME value).
        // The SWC-INTERNAL-BEHAVIOR SHORT-NAME is stored as "internalBehaviorName" so the exporter
        // can reproduce the original name instead of synthesising "${compName}_InternalBehavior",
        // preventing data-loss on export→re-import when the vendor ARXML uses a non-standard name.
        var behaviorSpecName: String? = null
        var internalBehaviorName: String? = null
        val behaviorsEl =
            el.getChild(ArxmlSchema.ELEM_INTERNAL_BEHAVIORS, arNs)
                ?: el.getChild(ArxmlSchema.ELEM_INTERNAL_BEHAVIORS, Namespace.NO_NAMESPACE)
        if (behaviorsEl != null) {
            for (behaviorEl in behaviorsEl.children) {
                val behaviorName =
                    behaviorEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs)
                        ?: "${shortName}_InternalBehavior"
                // Capture the first internal-behavior name encountered so the exporter can
                // reproduce the original SHORT-NAME instead of synthesising a new one.
                if (internalBehaviorName == null) {
                    internalBehaviorName = behaviorName
                }
                val behaviorPath = ArxmlPath.append(swcPath, behaviorName)

                // Build a trigger map: runnablePath → RunnableTrigger
                // from the EVENTS block (two-pass join)
                val triggerMap = buildTriggerMap(behaviorEl, arNs, behaviorPath)

                val runnablesContainer =
                    behaviorEl.getChild(ArxmlSchema.ELEM_RUNNABLES, arNs)
                        ?: behaviorEl.getChild(ArxmlSchema.ELEM_RUNNABLES, Namespace.NO_NAMESPACE)
                val runnableSource = runnablesContainer ?: behaviorEl

                for (
                runnableEl in runnableSource
                    .getChildrenDual(ArxmlSchema.ELEM_RUNNABLE_ENTITY, arNs)
                ) {
                    val runnableName = runnableEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: "UnnamedRunnable"
                    val runnablePath = ArxmlPath.append(behaviorPath, runnableName)
                    val trigger = triggerMap[runnablePath]
                    val metadata =
                        if (trigger != null && trigger != RunnableTrigger.UNKNOWN) {
                            mapOf("trigger" to KumlMetaValue.Text(trigger.name))
                        } else {
                            emptyMap()
                        }
                    operations.add(
                        UmlOperation(
                            id = UUID.randomUUID().toString(),
                            name = runnableName,
                            stereotypes = listOf(ArxmlSchema.STEREOTYPE_RUNNABLE),
                            metadata = metadata,
                        ),
                    )
                }

                // BehaviorSpec: record name in metadata so it survives roundtrip.
                // Only the first BEHAVIOR-SPEC encountered per SWC-INTERNAL-BEHAVIOR is retained.
                if (behaviorSpecName == null) {
                    val behaviorSpecEl =
                        behaviorEl.getChild(ArxmlSchema.ELEM_BEHAVIOR_SPEC, arNs)
                            ?: behaviorEl.getChild(ArxmlSchema.ELEM_BEHAVIOR_SPEC, Namespace.NO_NAMESPACE)
                    if (behaviorSpecEl != null) {
                        behaviorSpecName =
                            behaviorSpecEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs)
                                ?: "${shortName}_BehaviorSpec"
                    }
                }
            }
        }

        val componentMetadata =
            mutableMapOf<String, KumlMetaValue>(
                "kind" to KumlMetaValue.Text(kind),
            )
        if (behaviorSpecName != null) {
            componentMetadata["behaviorSpec"] = KumlMetaValue.Text(behaviorSpecName)
        }
        if (internalBehaviorName != null) {
            componentMetadata["internalBehaviorName"] = KumlMetaValue.Text(internalBehaviorName)
        }

        return UmlComponent(
            id = UUID.randomUUID().toString(),
            name = shortName,
            ports = ports,
            operations = operations,
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_SOFTWARE_COMPONENT),
            metadata = componentMetadata,
        )
    }

    /** Builds a map from runnable absolute path → [RunnableTrigger] by reading the EVENTS block. */
    private fun buildTriggerMap(
        behaviorEl: Element,
        arNs: Namespace,
        behaviorPath: String,
    ): Map<String, RunnableTrigger> {
        val result = mutableMapOf<String, RunnableTrigger>()
        val eventsEl =
            behaviorEl.getChild(ArxmlSchema.ELEM_EVENTS, arNs)
                ?: behaviorEl.getChild(ArxmlSchema.ELEM_EVENTS, Namespace.NO_NAMESPACE)
                ?: return result

        val knownEventNames =
            setOf(
                ArxmlSchema.ELEM_TIMING_EVENT,
                ArxmlSchema.ELEM_DATA_RECEIVED_EVENT,
                ArxmlSchema.ELEM_OPERATION_INVOKED_EVENT,
                ArxmlSchema.ELEM_INIT_EVENT,
                ArxmlSchema.ELEM_SWC_MODE_SWITCH_EVENT,
            )

        for (eventEl in eventsEl.children) {
            if (eventEl.name !in knownEventNames) continue
            val trigger = RunnableTrigger.fromArxmlElementName(eventEl.name)
            val startOnEventRef =
                eventEl.getChildText(ArxmlSchema.ELEM_START_ON_EVENT_REF, arNs)
                    ?: eventEl.getChildText(ArxmlSchema.ELEM_START_ON_EVENT_REF, Namespace.NO_NAMESPACE)
                    ?: continue
            val normRef = ArxmlPath.normaliseTref(startOnEventRef) ?: continue
            result[normRef] = trigger
        }
        return result
    }

    private fun buildPort(
        portEl: Element,
        arNs: Namespace,
        direction: String,
        swcPath: String,
        pathIndex: Map<String, Element>,
        unresolved: MutableList<ImportResult.UnresolvedRef>,
    ): UmlPort {
        val portName = portEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: "UnnamedPort"
        val portPath = ArxmlPath.append(swcPath, portName)

        val trefElemName =
            if (direction == "provided") {
                ArxmlSchema.ELEM_PROVIDED_INTERFACE_TREF
            } else {
                ArxmlSchema.ELEM_REQUIRED_INTERFACE_TREF
            }

        val trefText =
            portEl.getChildText(trefElemName, arNs)
                ?: portEl.getChildText(trefElemName, Namespace.NO_NAMESPACE)

        val metadata =
            mutableMapOf<String, KumlMetaValue>(
                "direction" to KumlMetaValue.Text(direction),
            )

        if (trefText != null) {
            val normPath = ArxmlPath.normaliseTref(trefText)
            if (normPath != null) {
                if (pathIndex.containsKey(normPath)) {
                    metadata["interfaceRef"] = KumlMetaValue.Text(normPath)
                } else {
                    unresolved.add(
                        ImportResult.UnresolvedRef(
                            fromPath = portPath,
                            targetPath = normPath,
                            kind = "interface-tref",
                        ),
                    )
                }
            }
        }

        return UmlPort(
            id = UUID.randomUUID().toString(),
            name = portName,
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_AUTOSAR_PORT),
            metadata = metadata,
        )
    }

    private fun buildInterface(
        el: Element,
        arNs: Namespace,
        isService: Boolean,
    ): UmlInterface {
        val shortName = el.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: "UnnamedInterface"
        return UmlInterface(
            id = UUID.randomUUID().toString(),
            name = shortName,
            stereotypes = listOf(ArxmlSchema.STEREOTYPE_COM_INTERFACE),
            metadata = if (isService) mapOf("isService" to KumlMetaValue.Text("true")) else emptyMap(),
        )
    }

    private fun detectVersion(
        root: Element,
        warnings: MutableList<String>,
    ): ArxmlVersion {
        if (version != null) return version
        val detected = ArxmlVersion.detect(root)
        if (detected == null) {
            warnings.add("Root element does not carry AUTOSAR R4.x namespace; defaulting to R22_11")
            return ArxmlVersion.R22_11
        }
        // Namespace-based detection succeeded — trust it.
        // Optionally cross-check with xsi:schemaLocation for an informational warning only;
        // the detected version is returned regardless (schemaLocation is optional in AUTOSAR).
        val schemaLocation =
            root.getAttributeValue(
                "schemaLocation",
                Namespace.getNamespace("xsi", ArxmlSchema.XSI_NS),
            )
        if (schemaLocation == null) {
            warnings.add(
                "xsi:schemaLocation absent — version determined from namespace as ${detected.name}",
            )
        }
        return detected
    }

    // ── JDOM2 dual-namespace helpers ──────────────────────────────────────────

    private fun Element.getChildrenDual(
        name: String,
        ns: Namespace,
    ): List<Element> = getChildren(name, ns).ifEmpty { getChildren(name, Namespace.NO_NAMESPACE) }

    private fun Element.getTextDual(
        name: String,
        ns: Namespace,
    ): String? =
        getChildText(name, ns)?.takeIf { it.isNotEmpty() }
            ?: getChildText(name, Namespace.NO_NAMESPACE)?.takeIf { it.isNotEmpty() }
}
