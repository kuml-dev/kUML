package dev.kuml.io.arxml

import org.jdom2.Element

/**
 * Known AUTOSAR Classic Platform schema releases.
 *
 * All releases R19-11 through R23-11 share the same xmlns URI
 * (`http://autosar.org/schema/r4.0`). The concrete release is identified
 * via the `xsi:schemaLocation` attribute value which contains a token such as
 * `AUTOSAR_00048.xsd` (R19-11) or `AUTOSAR_00051.xsd` (R22-11).
 *
 * Use [detect] to read both xmlns and schemaLocation from a root element.
 * Use [fromNamespace] when only the namespace URI is known (returns `null`
 * because the URI alone cannot disambiguate — the caller should fall back to
 * a default, typically [R22_11], and emit a warning).
 *
 * V3.1.33 — initial implementation.
 */
public enum class ArxmlVersion(
    /** The `xmlns` / namespace URI used in the ARXML file. */
    public val namespaceUri: String,
    /** The `xsi:schemaLocation` schema-file token (without `.xsd` suffix). */
    public val schemaLabel: String,
) {
    R19_11("http://autosar.org/schema/r4.0", "AUTOSAR_00048"),
    R20_11("http://autosar.org/schema/r4.0", "AUTOSAR_00049"),
    R21_11("http://autosar.org/schema/r4.0", "AUTOSAR_00050"),
    R22_11("http://autosar.org/schema/r4.0", "AUTOSAR_00051"),
    R23_11("http://autosar.org/schema/r4.0", "AUTOSAR_00052"),
    ;

    public companion object {
        /**
         * Returns a version matching the given [namespaceUri], or `null` if the URI
         * is unknown. Note: since all R4.x releases share the same URI this method
         * returns the first matching entry ([R19_11]) and **cannot** disambiguate
         * between releases. Use [detect] for reliable version detection.
         */
        public fun fromNamespace(uri: String): ArxmlVersion? = entries.firstOrNull { it.namespaceUri == uri }

        /**
         * Detects the AUTOSAR version from a root JDOM2 [Element] by inspecting both
         * the xmlns URI and the `xsi:schemaLocation` attribute.
         *
         * Detection priority:
         * 1. Find an entry whose [schemaLabel] appears as a token in `xsi:schemaLocation`.
         * 2. Fall back to [R22_11] with a warning if `xsi:schemaLocation` is absent or
         *    contains an unrecognised token (callers receive the warning via
         *    [ArxmlParseResult.warnings]).
         *
         * Returns `null` if the root element does not carry the AUTOSAR R4.x namespace URI
         * at all (i.e. the file is not an AUTOSAR ARXML document).
         */
        public fun detect(rootElement: Element): ArxmlVersion? {
            // Check the element's own namespace plus declared additional namespaces
            val uri =
                (listOf(rootElement.namespace) + rootElement.additionalNamespaces)
                    .firstOrNull { it.uri == ArxmlSchema.AUTOSAR_NS_R40 }
                    ?.uri
                    ?: return null

            val schemaLocation =
                rootElement.getAttributeValue(
                    "schemaLocation",
                    org.jdom2.Namespace.getNamespace("xsi", ArxmlSchema.XSI_NS),
                )

            if (schemaLocation != null) {
                val token =
                    schemaLocation
                        .split("\\s+".toRegex())
                        .firstOrNull { t -> entries.any { v -> t.contains(v.schemaLabel) } }
                if (token != null) {
                    return entries.firstOrNull { v -> token.contains(v.schemaLabel) }
                }
            }

            // namespace matches but schemaLocation absent/unrecognised — caller adds warning
            return fromNamespace(uri)
        }
    }
}
