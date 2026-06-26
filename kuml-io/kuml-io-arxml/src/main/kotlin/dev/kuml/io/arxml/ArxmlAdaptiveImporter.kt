package dev.kuml.io.arxml

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.kerml.KermlFeature
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.uml.UmlPackage
import org.jdom2.Element
import org.jdom2.Namespace
import java.io.File
import java.io.StringReader
import java.util.UUID

/**
 * Imports an AUTOSAR Adaptive Platform ARXML file and produces an [AdaptiveImportResult]
 * containing a [Sysml2Model].
 *
 * **Two-pass algorithm**:
 * - Pass 1: index all SHORT-NAME bearing elements by their absolute AUTOSAR path.
 * - Pass 2: build a [Sysml2Model] whose definitions are [PartDefinition] instances.
 *
 * **Mapping**:
 * - `SERVICE-INSTANCE`                     → [PartDefinition] + metadata `kind=ServiceInstance`, stereotype `ServiceInstance`
 * - `ADAPTIVE-APPLICATION-SW-COMPONENT-TYPE` → [PartDefinition] + metadata `kind=AdaptiveApplication`, stereotype `AdaptiveApplication`
 * - `MACHINE-DESIGN`                        → [PartDefinition] + metadata `kind=Machine`, stereotype `Machine`
 * - `SERVICE-MANIFEST`                      → [PartDefinition] + metadata `kind=Manifest`, `manifestKind=service`; leaf values as [KermlFeature]s
 * - `MACHINE-MANIFEST`                      → [PartDefinition] + metadata `kind=Manifest`, `manifestKind=machine`; leaf values as [KermlFeature]s
 *
 * **Contract**: never throws on partial input — missing elements produce [AdaptiveImportResult.warnings],
 * dangling refs produce [AdaptiveImportResult.unresolved]. Only structurally invalid XML causes an exception.
 *
 * @property version When non-null, overrides auto-detection from the root element.
 *
 * V3.1.35 — initial implementation.
 */
public class ArxmlAdaptiveImporter(
    public val version: ArxmlAdaptiveVersion? = null,
) {
    /**
     * Parses [file] and returns an [AdaptiveImportResult] containing the [Sysml2Model].
     */
    public fun import(file: File): AdaptiveImportResult {
        val doc = ArxmlSax.secureBuilder().build(file)
        return buildResult(doc.rootElement)
    }

    /**
     * Parses the ARXML [xml] string and returns an [AdaptiveImportResult].
     */
    public fun importFromString(xml: String): AdaptiveImportResult {
        val doc = ArxmlSax.secureBuilder().build(StringReader(xml))
        return buildResult(doc.rootElement)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun buildResult(root: Element): AdaptiveImportResult {
        val warnings = mutableListOf<String>()
        val unresolved = mutableListOf<ImportResult.UnresolvedRef>()

        val detectedVersion = detectVersion(root, warnings)
        val arNs = ArxmlSchema.arNamespace(detectedVersion)

        // ── PASS 1: path index ────────────────────────────────────────────────
        val pathIndex = mutableMapOf<String, Element>()
        val arPackagesRoot =
            root.getChild(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
                ?: root.getChild(ArxmlSchema.ELEM_AR_PACKAGES, Namespace.NO_NAMESPACE)

        if (arPackagesRoot != null) {
            indexPackages(arPackagesRoot, arNs, "/", pathIndex)
        } else {
            warnings.add("No AR-PACKAGES element found in root AUTOSAR Adaptive document")
        }

        // ── PASS 2: build SysML2 definitions ─────────────────────────────────
        val definitions = mutableListOf<PartDefinition>()
        if (arPackagesRoot != null) {
            for (pkgEl in arPackagesRoot.getChildrenDual(ArxmlSchema.ELEM_AR_PACKAGE, arNs)) {
                collectDefinitions(pkgEl, arNs, "/", definitions, pathIndex, warnings, unresolved)
            }
        }

        val allIds = definitions.map { it.id }
        val diagram = BdDiagram(name = "AUTOSAR Adaptive Platform", elementIds = allIds)
        val sysml2Model =
            Sysml2Model(
                name = "AUTOSAR Adaptive Import",
                definitions = definitions,
                diagrams = listOf(diagram),
            )

        // Also expose as a KumlModel (language=UML, level=PIM) wrapping the root package
        // so callers that only work with KumlModel can still access the import result.
        val kumlModel =
            KumlModel(
                root =
                    UmlPackage(
                        id = UUID.randomUUID().toString(),
                        name = "AUTOSAR-Adaptive",
                        members = emptyList(),
                    ),
                language = ModelingLanguage.UML,
                level = ModelLevel.PIM,
                name = "AUTOSAR Adaptive Import",
            )

        return AdaptiveImportResult(
            model = sysml2Model,
            kumlModel = kumlModel,
            version = detectedVersion,
            warnings = warnings,
            unresolved = unresolved,
        )
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

            val elementsEl =
                pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, arNs)
                    ?: pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, Namespace.NO_NAMESPACE)
            if (elementsEl != null) {
                for (child in elementsEl.children) {
                    val childName = child.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: continue
                    index[ArxmlPath.append(pkgPath, childName)] = child
                }
            }

            val nestedArPkgs =
                pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
                    ?: pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, Namespace.NO_NAMESPACE)
            if (nestedArPkgs != null) {
                indexPackages(nestedArPkgs, arNs, pkgPath, index)
            }
        }
    }

    // ── Pass 2: SysML2 definition building ───────────────────────────────────

    @Suppress("LongParameterList")
    private fun collectDefinitions(
        pkgEl: Element,
        arNs: Namespace,
        parentPath: String,
        definitions: MutableList<PartDefinition>,
        pathIndex: Map<String, Element>,
        warnings: MutableList<String>,
        unresolved: MutableList<ImportResult.UnresolvedRef>,
    ) {
        val shortName = pkgEl.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs) ?: "UnnamedPackage"
        val pkgPath = ArxmlPath.append(parentPath, shortName)

        // Recurse into sub-packages
        val nestedArPkgs =
            pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
                ?: pkgEl.getChild(ArxmlSchema.ELEM_AR_PACKAGES, Namespace.NO_NAMESPACE)
        if (nestedArPkgs != null) {
            for (subPkg in nestedArPkgs.getChildrenDual(ArxmlSchema.ELEM_AR_PACKAGE, arNs)) {
                collectDefinitions(subPkg, arNs, pkgPath, definitions, pathIndex, warnings, unresolved)
            }
        }

        val elementsEl =
            pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, arNs)
                ?: pkgEl.getChild(ArxmlSchema.ELEM_ELEMENTS, Namespace.NO_NAMESPACE)
        if (elementsEl != null) {
            for (child in elementsEl.children) {
                val localName = child.name
                when (localName) {
                    ArxmlSchema.ELEM_SERVICE_INSTANCE ->
                        definitions.add(buildServiceInstanceDefinition(child, arNs, warnings))

                    ArxmlSchema.ELEM_ADAPTIVE_APPLICATION_SWC ->
                        definitions.add(buildAdaptiveApplicationDefinition(child, arNs, warnings))

                    ArxmlSchema.ELEM_MACHINE_DESIGN ->
                        definitions.add(buildMachineDefinition(child, arNs, warnings))

                    ArxmlSchema.ELEM_SERVICE_MANIFEST ->
                        definitions.add(buildManifestDefinition(child, arNs, "service", warnings))

                    ArxmlSchema.ELEM_MACHINE_MANIFEST ->
                        definitions.add(buildManifestDefinition(child, arNs, "machine", warnings))

                    ArxmlSchema.ELEM_SHORT_NAME -> { /* handled above */ }

                    else ->
                        warnings.add(
                            "Unknown Adaptive Platform element <$localName> in package '$shortName' — skipped",
                        )
                }
            }
        }
    }

    private fun buildServiceInstanceDefinition(
        el: Element,
        arNs: Namespace,
        warnings: MutableList<String>,
    ): PartDefinition {
        val shortName = el.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs)
        if (shortName == null) {
            warnings.add("SERVICE-INSTANCE element missing SHORT-NAME — assigned placeholder name")
        }
        val name = shortName ?: "UnnamedServiceInstance_${UUID.randomUUID().toString().take(8)}"
        return PartDefinition(
            id = UUID.randomUUID().toString(),
            name = name,
            metadata =
                mapOf(
                    "kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_SERVICE_INSTANCE),
                    "stereotype" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_SERVICE_INSTANCE),
                    "arxmlElement" to KumlMetaValue.Text(ArxmlSchema.ELEM_SERVICE_INSTANCE),
                ),
        )
    }

    private fun buildAdaptiveApplicationDefinition(
        el: Element,
        arNs: Namespace,
        warnings: MutableList<String>,
    ): PartDefinition {
        val shortName = el.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs)
        if (shortName == null) {
            warnings.add(
                "ADAPTIVE-APPLICATION-SW-COMPONENT-TYPE element missing SHORT-NAME — assigned placeholder name",
            )
        }
        val name = shortName ?: "UnnamedAdaptiveApp_${UUID.randomUUID().toString().take(8)}"
        return PartDefinition(
            id = UUID.randomUUID().toString(),
            name = name,
            metadata =
                mapOf(
                    "kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_ADAPTIVE_APPLICATION),
                    "stereotype" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_ADAPTIVE_APPLICATION),
                    "arxmlElement" to KumlMetaValue.Text(ArxmlSchema.ELEM_ADAPTIVE_APPLICATION_SWC),
                ),
        )
    }

    private fun buildMachineDefinition(
        el: Element,
        arNs: Namespace,
        warnings: MutableList<String>,
    ): PartDefinition {
        val shortName = el.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs)
        if (shortName == null) {
            warnings.add("MACHINE-DESIGN element missing SHORT-NAME — assigned placeholder name")
        }
        val name = shortName ?: "UnnamedMachine_${UUID.randomUUID().toString().take(8)}"
        return PartDefinition(
            id = UUID.randomUUID().toString(),
            name = name,
            metadata =
                mapOf(
                    "kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_MACHINE),
                    "stereotype" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_MACHINE),
                    "arxmlElement" to KumlMetaValue.Text(ArxmlSchema.ELEM_MACHINE_DESIGN),
                ),
        )
    }

    private fun buildManifestDefinition(
        el: Element,
        arNs: Namespace,
        manifestKind: String,
        warnings: MutableList<String>,
    ): PartDefinition {
        val shortName = el.getTextDual(ArxmlSchema.ELEM_SHORT_NAME, arNs)
        if (shortName == null) {
            val elemName = if (manifestKind == "service") ArxmlSchema.ELEM_SERVICE_MANIFEST else ArxmlSchema.ELEM_MACHINE_MANIFEST
            warnings.add("$elemName element missing SHORT-NAME — assigned placeholder name")
        }
        val name = shortName ?: "UnnamedManifest_${UUID.randomUUID().toString().take(8)}"

        // Collect leaf-value elements as KermlFeatures
        val features = mutableListOf<KermlFeature>()
        collectManifestLeaves(el, arNs, name, features)

        return PartDefinition(
            id = UUID.randomUUID().toString(),
            name = name,
            features = features,
            metadata =
                mapOf(
                    "kind" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_MANIFEST),
                    "stereotype" to KumlMetaValue.Text(ArxmlSchema.STEREOTYPE_MANIFEST),
                    "manifestKind" to KumlMetaValue.Text(manifestKind),
                    "arxmlElement" to
                        KumlMetaValue.Text(
                            if (manifestKind == "service") ArxmlSchema.ELEM_SERVICE_MANIFEST else ArxmlSchema.ELEM_MACHINE_MANIFEST,
                        ),
                ),
        )
    }

    /**
     * Recursively collects leaf text-value children of a manifest element
     * and represents each as a [KermlFeature] with the element name as feature name
     * and the text content as a metadata `value` entry.
     */
    private fun collectManifestLeaves(
        el: Element,
        arNs: Namespace,
        parentName: String,
        features: MutableList<KermlFeature>,
    ) {
        for (child in el.children) {
            val childName = child.name
            if (childName == ArxmlSchema.ELEM_SHORT_NAME) continue
            val text = child.textTrim
            if (text.isNotEmpty() && child.children.isEmpty()) {
                // Leaf element with text value
                features.add(
                    KermlFeature(
                        id = UUID.randomUUID().toString(),
                        name = childName,
                        metadata = mapOf("value" to KumlMetaValue.Text(text)),
                    ),
                )
            } else if (child.children.isNotEmpty()) {
                // Non-leaf: recurse
                collectManifestLeaves(child, arNs, "$parentName/$childName", features)
            }
        }
    }

    private fun detectVersion(
        root: Element,
        warnings: MutableList<String>,
    ): ArxmlAdaptiveVersion {
        if (version != null) return version
        val detected = ArxmlAdaptiveVersion.detect(root, warnings)
        if (detected == null) {
            warnings.add(
                "Root element does not appear to be an AUTOSAR Adaptive Platform document; defaulting to R23_11",
            )
            return ArxmlAdaptiveVersion.R23_11
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

/**
 * Result of importing an AUTOSAR Adaptive Platform ARXML file via [ArxmlAdaptiveImporter].
 *
 * @property model       Resulting SysML 2 model with Adaptive Platform definitions as [PartDefinition]s.
 * @property kumlModel   KumlModel wrapper for callers that work with the KumlModel abstraction.
 * @property version     Detected AUTOSAR Adaptive Platform version.
 * @property warnings    Non-fatal parse warnings.
 * @property unresolved  Dangling cross-references that could not be resolved.
 *
 * V3.1.35 — initial implementation.
 */
public data class AdaptiveImportResult(
    /** Resulting SysML 2 model — Adaptive Platform elements as [dev.kuml.sysml2.PartDefinition]s. */
    val model: Sysml2Model,
    /** KumlModel wrapper for callers that use the KumlModel abstraction. */
    val kumlModel: KumlModel,
    /** Detected or overridden AUTOSAR Adaptive Platform schema version. */
    val version: ArxmlAdaptiveVersion,
    /** Non-fatal parse warnings (unknown elements, missing SHORT-NAMEs, fallback versions). */
    val warnings: List<String> = emptyList(),
    /** Dangling cross-references that could not be resolved during import. */
    val unresolved: List<ImportResult.UnresolvedRef> = emptyList(),
)
