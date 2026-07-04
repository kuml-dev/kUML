package dev.kuml.io.emf

import org.eclipse.emf.ecore.xmi.XMLResource

/**
 * Shared XXE / XML-bomb hardening for EMF XMI loads.
 *
 * EMF's [org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl] delegates to a
 * SAX parser that — with default options — resolves external entities and
 * processes DOCTYPE declarations. A crafted `.xmi` / `.uml` file could therefore
 * carry an XXE payload (`<!ENTITY xxe SYSTEM "file:///etc/passwd">`) or an
 * entity-expansion ("billion laughs") bomb.
 *
 * This mirrors the hardening already applied to the ARXML
 * ([dev.kuml.io.arxml.ArxmlSax]) and BPMN
 * ([dev.kuml.io.bpmn.BpmnXmlImporter]) importers:
 *  - `disallow-doctype-decl=true`     — any DOCTYPE declaration aborts the parse.
 *  - `external-general-entities=false`  — external general entities are not resolved.
 *  - `external-parameter-entities=false`— external parameter entities are not resolved.
 *  - `load-external-dtd=false`          — no external DTD is fetched.
 *
 * Because DOCTYPE declarations are disallowed outright, the entity-expansion
 * bomb vector is closed as a side effect (no `<!ENTITY>` can be declared).
 *
 * Usage: create the resource explicitly (do **not** use the eager
 * `resourceSet.getResource(uri, true)` overload, which loads with default
 * options) and pass [loadOptions] to `resource.load(...)`.
 */
internal object EmfXmlSecurity {
    /**
     * SAX parser features that disable DOCTYPE processing and external entity
     * resolution. Applied via [XMLResource.OPTION_PARSER_FEATURES].
     */
    private val secureParserFeatures: Map<String, Boolean> =
        mapOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )

    /**
     * Load options that harden the underlying SAX parser against XXE and
     * XML-entity-expansion attacks. Pass to `resource.load(secureLoadOptions())`.
     */
    fun secureLoadOptions(): Map<Any?, Any?> = mapOf(XMLResource.OPTION_PARSER_FEATURES to secureParserFeatures)
}
