package dev.kuml.io.arxml

import org.jdom2.input.SAXBuilder
import org.jdom2.input.sax.XMLReaders

/**
 * Shared XXE-hardened SAXBuilder factory for all ARXML parsers.
 *
 * Security guarantees (all applied in [secureBuilder]):
 * - `disallow-doctype-decl=true`: any DOCTYPE declaration throws immediately,
 *   killing both XXE injection and billion-laughs entity-expansion attacks.
 * - `external-general-entities=false`: external entity references disabled.
 * - `external-parameter-entities=false`: external parameter entity references disabled.
 * - `load-external-dtd=false`: no DTD loading from external sources.
 * - `expandEntities=false`: entity expansion disabled.
 *
 * Single source of truth — [ArxmlReader] and [ArxmlClassicImporter] both delegate
 * to this object so that security flags are never duplicated or accidentally omitted.
 *
 * V3.1.34 — extracted from ArxmlReader to share with ArxmlClassicImporter.
 */
internal object ArxmlSax {
    /**
     * Returns an XXE-hardened non-validating [SAXBuilder].
     *
     * Each call returns a **new** [SAXBuilder] instance (SAXBuilder is not thread-safe).
     */
    internal fun secureBuilder(): SAXBuilder {
        val sb = SAXBuilder(XMLReaders.NONVALIDATING)
        sb.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        sb.setFeature("http://xml.org/sax/features/external-general-entities", false)
        sb.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        sb.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        sb.expandEntities = false
        return sb
    }
}
