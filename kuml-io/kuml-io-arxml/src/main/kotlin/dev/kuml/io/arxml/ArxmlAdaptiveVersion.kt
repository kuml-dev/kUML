package dev.kuml.io.arxml

import org.jdom2.Element
import org.jdom2.Namespace

/**
 * Known AUTOSAR Adaptive Platform schema releases.
 *
 * The Adaptive Platform releases share the same xmlns URI as Classic R4.x
 * (`http://autosar.org/schema/r4.0`). Version detection CANNOT rely on the namespace URI
 * alone — it must inspect the `xsi:schemaLocation` attribute for an `AUTOSAR_AP_*` token
 * AND / OR detect the presence of Adaptive-specific root children.
 *
 * Use [detect] to identify the Adaptive release from a root element.
 * Use [isAdaptiveDocument] to distinguish an Adaptive document from a Classic one.
 *
 * V3.1.35 — initial implementation.
 */
public enum class ArxmlAdaptiveVersion(
    /** The `xmlns` / namespace URI used in the ARXML file. */
    public val namespaceUri: String,
    /** The `xsi:schemaLocation` schema-file token (without `.xsd` suffix). */
    public val schemaLabel: String,
) {
    R19_03("http://autosar.org/schema/r4.0", "AUTOSAR_AP_00047"),
    R20_11("http://autosar.org/schema/r4.0", "AUTOSAR_AP_00049"),
    R21_11("http://autosar.org/schema/r4.0", "AUTOSAR_AP_00050"),
    R22_11("http://autosar.org/schema/r4.0", "AUTOSAR_AP_00051"),
    R23_11("http://autosar.org/schema/r4.0", "AUTOSAR_AP_00052"),
    ;

    public companion object {
        /**
         * Returns `true` when the root element looks like an AUTOSAR Adaptive Platform document.
         *
         * Detection strategy (both checks are applied; either is sufficient):
         * 1. The `xsi:schemaLocation` attribute contains a token with `AUTOSAR_AP_` prefix.
         * 2. The root element has at least one Adaptive-specific child element
         *    (`SERVICE-INSTANCE`, `ADAPTIVE-APPLICATION-SW-COMPONENT-TYPE`, or `MACHINE-DESIGN`).
         *
         * Note: NEVER key off the namespace URI alone — Adaptive shares the same R4.x URI as Classic.
         */
        public fun isAdaptiveDocument(root: Element): Boolean {
            // Check 1: xsi:schemaLocation contains an AUTOSAR_AP_ token
            val xsiNs = Namespace.getNamespace("xsi", ArxmlSchema.XSI_NS)
            val schemaLocation = root.getAttributeValue("schemaLocation", xsiNs)
            if (schemaLocation != null) {
                val hasApToken =
                    schemaLocation.split("\\s+".toRegex()).any { token ->
                        ArxmlSchema.isAdaptiveSchemaLabel(token)
                    }
                if (hasApToken) return true
            }

            // Check 2: presence of Adaptive-specific root children (traverse AR-PACKAGES)
            val arNs = Namespace.getNamespace(ArxmlSchema.AUTOSAR_NS_R40)
            val arPackages =
                root.getChild(ArxmlSchema.ELEM_AR_PACKAGES, arNs)
                    ?: root.getChild(ArxmlSchema.ELEM_AR_PACKAGES, Namespace.NO_NAMESPACE)
            if (arPackages != null && hasAdaptiveElements(arPackages, arNs)) {
                return true
            }
            return false
        }

        /**
         * Detects the AUTOSAR Adaptive Platform version from a root JDOM2 [Element].
         *
         * Returns `null` if the document does not appear to be an Adaptive Platform document
         * (i.e. [isAdaptiveDocument] returns `false`).
         *
         * Detection priority:
         * 1. Find an entry whose [schemaLabel] appears in `xsi:schemaLocation`.
         * 2. Fall back to [R23_11] with a warning when schemaLocation is absent but Adaptive
         *    elements are present.
         *
         * The [warnings] list receives a message whenever a fallback occurs.
         */
        public fun detect(
            root: Element,
            warnings: MutableList<String> = mutableListOf(),
        ): ArxmlAdaptiveVersion? {
            if (!isAdaptiveDocument(root)) return null

            val xsiNs = Namespace.getNamespace("xsi", ArxmlSchema.XSI_NS)
            val schemaLocation = root.getAttributeValue("schemaLocation", xsiNs)

            if (schemaLocation != null) {
                val tokens = schemaLocation.split("\\s+".toRegex())
                for (token in tokens) {
                    val match = entries.firstOrNull { v -> token.contains(v.schemaLabel) }
                    if (match != null) return match
                }
            }

            // Adaptive elements detected but schemaLocation absent or unrecognised — fall back to R23_11
            warnings.add(
                "AUTOSAR Adaptive Platform document detected but xsi:schemaLocation is absent " +
                    "or contains no recognised AP schema token — defaulting to R23_11",
            )
            return R23_11
        }

        // ── Private helpers ───────────────────────────────────────────────────

        private fun hasAdaptiveElements(
            arPackages: Element,
            arNs: Namespace,
        ): Boolean {
            val adaptiveLocalNames =
                setOf(
                    ArxmlSchema.ELEM_SERVICE_INSTANCE,
                    ArxmlSchema.ELEM_ADAPTIVE_APPLICATION_SWC,
                    ArxmlSchema.ELEM_MACHINE_DESIGN,
                    ArxmlSchema.ELEM_SERVICE_MANIFEST,
                    ArxmlSchema.ELEM_MACHINE_MANIFEST,
                )

            fun searchElement(el: Element): Boolean {
                if (el.name in adaptiveLocalNames) return true
                for (child in el.children) {
                    if (searchElement(child)) return true
                }
                return false
            }

            for (pkgEl in arPackages.children) {
                if (searchElement(pkgEl)) return true
            }
            return false
        }
    }
}
