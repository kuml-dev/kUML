package dev.kuml.io.arxml

import dev.kuml.core.model.KumlModel

/**
 * High-level result of importing an AUTOSAR Classic ARXML file via [ArxmlClassicImporter].
 *
 * Unlike the lower-level [ArxmlParseResult] (which exposes a raw [dev.kuml.uml.UmlPackage]),
 * this type wraps the result in a [KumlModel] and separately surfaces non-fatal parse issues
 * so that callers can distinguish between dangling cross-references and genuine warnings.
 *
 * **Contract**: [ArxmlClassicImporter] **never throws** for partial or incomplete input.
 * Missing optional elements produce [warnings]; dangling AUTOSAR path references produce
 * [unresolved] entries. Only structurally invalid XML (malformed, DOCTYPE injection) causes
 * an exception.
 *
 * @property model       Resulting kUML model with AUTOSAR profile stereotypes applied.
 * @property warnings    Non-fatal parse warnings (e.g. unknown elements, missing schemaLocation).
 * @property unresolved  Dangling AUTOSAR cross-references that could not be resolved.
 *
 * V3.1.34 — initial implementation.
 */
public data class ImportResult(
    val model: KumlModel,
    val warnings: List<String> = emptyList(),
    val unresolved: List<UnresolvedRef> = emptyList(),
) {
    /**
     * A cross-reference that could not be resolved during import because the target AUTOSAR
     * path was not present in the document.
     *
     * @property fromPath   AUTOSAR absolute path of the element that carried the reference.
     * @property targetPath AUTOSAR absolute path that was referenced but not found.
     * @property kind       Human-readable reference kind, e.g. `"interface-tref"`, `"runnable-trigger"`.
     */
    public data class UnresolvedRef(
        val fromPath: String,
        val targetPath: String,
        val kind: String,
    )
}
