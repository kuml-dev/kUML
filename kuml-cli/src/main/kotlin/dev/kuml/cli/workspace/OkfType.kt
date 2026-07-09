package dev.kuml.cli.workspace

/**
 * The OKF `type:` frontmatter vocabulary (ADR-0011).
 *
 * Mirrors the frontmatter-vocabulary table in ADR-0011. SysML 2 diagram types are
 * intentionally omitted here — FT-2 owns the authoritative SysML 2 vocabulary; this
 * spike only needs enough of the UML/C4/ERM surface to prove the parse → validate →
 * render pipeline end-to-end.
 *
 * @property id The exact string expected in the `type:` frontmatter field.
 * @property requiresKumlBlock Whether a document of this type must contain exactly
 *  one ` ```kuml ` fenced code block (diagram types) or none (prose/collection types).
 */
internal enum class OkfType(
    val id: String,
    val requiresKumlBlock: Boolean,
) {
    KUML_WORKSPACE("KumlWorkspace", false),
    CONCEPT_COLLECTION("ConceptCollection", false),
    CONCEPT("Concept", false),
    UML_CLASS_DIAGRAM("UmlClassDiagram", true),
    UML_STATE_MACHINE("UmlStateMachine", true),
    UML_SEQUENCE_DIAGRAM("UmlSequenceDiagram", true),
    UML_ACTIVITY_DIAGRAM("UmlActivityDiagram", true),
    UML_COMPONENT_DIAGRAM("UmlComponentDiagram", true),
    UML_USE_CASE_DIAGRAM("UmlUseCaseDiagram", true),
    C4_CONTEXT_DIAGRAM("C4ContextDiagram", true),
    C4_CONTAINER_DIAGRAM("C4ContainerDiagram", true),
    C4_COMPONENT_DIAGRAM("C4ComponentDiagram", true),
    C4_CODE_DIAGRAM("C4CodeDiagram", true),
    ERM_DIAGRAM("ErmDiagram", true),
    ARTICLE("Article", false),
    GLOSSARY("Glossary", false),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Resolves a raw `type:` string to an [OkfType]. `null` means unknown/custom — allowed, WARNING only. */
        fun fromId(id: String?): OkfType? = id?.let { byId[it] }

        /** All types whose documents are expected to carry a ` ```kuml ` block. */
        val diagramTypes: Set<OkfType> = entries.filter { it.requiresKumlBlock }.toSet()
    }
}
