package dev.kuml.io.arxml

/**
 * AUTOSAR path helpers.
 *
 * AUTOSAR cross-references use **absolute paths** of the form `/Package/SubPackage/ElementName`,
 * NOT XML IDs or short-names alone. Every `*-TREF` / `*-REF` element carries one such path
 * as its text content.
 *
 * This object is intentionally small and pure (no I/O, no JDOM2 dependency) so that it is
 * trivially unit-testable.
 *
 * V3.1.34 — initial implementation.
 */
internal object ArxmlPath {
    /**
     * Builds an AUTOSAR absolute path from [segments], e.g. `of("Vehicle", "Brakes", "BrakeCtrl")`
     * → `"/Vehicle/Brakes/BrakeCtrl"`.
     */
    internal fun of(vararg segments: String): String = "/" + segments.joinToString("/")

    /**
     * Appends [child] to an existing absolute [parentPath], e.g.
     * `append("/Vehicle/Brakes", "BrakeCtrl")` → `"/Vehicle/Brakes/BrakeCtrl"`.
     */
    internal fun append(
        parentPath: String,
        child: String,
    ): String {
        val base = parentPath.trimEnd('/')
        return "$base/$child"
    }

    /**
     * Returns the last segment (short-name) of an AUTOSAR path, e.g.
     * `lastSegment("/Vehicle/Brakes/BrakeCtrl")` → `"BrakeCtrl"`.
     *
     * Returns the whole string if no `/` is present.
     */
    internal fun lastSegment(path: String): String = path.substringAfterLast('/', path)

    /**
     * Normalises a TREF path: trims whitespace, ensures it starts with `/`.
     * Returns `null` if the path is blank after trimming.
     */
    internal fun normaliseTref(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }
}
