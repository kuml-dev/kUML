package dev.kuml.workspace

/**
 * The OKF `type:` frontmatter vocabulary (ADR-0011, FT-2).
 *
 * Mirrors the frontmatter-vocabulary table in ADR-0011, extended in V3.6.1 (FT-2)
 * with the full SysML 2, BPMN, and Service Blueprint diagram surfaces. The exact `id`
 * strings match the canonical metamodel names used across the codebase
 * (`kuml-metamodel-sysml2`, `-bpmn`, `-blueprint`).
 *
 * @property id The exact string expected in the `type:` frontmatter field.
 * @property requiresKumlBlock Whether a document of this type must contain exactly
 *  one ` ```kuml ` fenced code block (diagram types) or none (prose/collection types).
 * @property since The OKF vocabulary version this type was introduced in (see
 *  [VOCABULARY_VERSION]).
 * @property description A one-line human-readable description, used by
 *  `kuml workspace info`/handbook generation and the JSON vocabulary contract
 *  (`/okf/kuml-okf-vocabulary.json`).
 */
public enum class OkfType(
    public val id: String,
    public val requiresKumlBlock: Boolean,
    public val since: String,
    public val description: String,
) {
    KUML_WORKSPACE(
        "KumlWorkspace",
        false,
        "1.0",
        "Root marker for an OKF knowledge workspace; no diagram content.",
    ),
    CONCEPT_COLLECTION(
        "ConceptCollection",
        false,
        "1.0",
        "A collection/index of related Concept documents.",
    ),
    CONCEPT("Concept", false, "1.0", "A single domain concept or definition."),
    UML_CLASS_DIAGRAM("UmlClassDiagram", true, "1.0", "UML class diagram."),
    UML_STATE_MACHINE("UmlStateMachine", true, "1.0", "UML state machine diagram."),
    UML_SEQUENCE_DIAGRAM("UmlSequenceDiagram", true, "1.0", "UML sequence diagram."),
    UML_ACTIVITY_DIAGRAM("UmlActivityDiagram", true, "1.0", "UML activity diagram."),
    UML_COMPONENT_DIAGRAM("UmlComponentDiagram", true, "1.0", "UML component diagram."),
    UML_USE_CASE_DIAGRAM("UmlUseCaseDiagram", true, "1.0", "UML use case diagram."),
    C4_CONTEXT_DIAGRAM("C4ContextDiagram", true, "1.0", "C4 model context diagram."),
    C4_CONTAINER_DIAGRAM("C4ContainerDiagram", true, "1.0", "C4 model container diagram."),
    C4_COMPONENT_DIAGRAM("C4ComponentDiagram", true, "1.0", "C4 model component diagram."),
    C4_CODE_DIAGRAM("C4CodeDiagram", true, "1.0", "C4 model code diagram."),
    ERM_DIAGRAM("ErmDiagram", true, "1.0", "Entity-relationship diagram."),
    ARTICLE("Article", false, "1.0", "Free-form prose article; no diagram content."),
    GLOSSARY("Glossary", false, "1.0", "A glossary of terms; no diagram content."),

    // SysML 2 (8) — V3.6.1 / FT-2
    SYSML2_BLOCK_DEFINITION(
        "Sysml2BlockDefinition",
        true,
        "1.0",
        "SysML 2 block definition diagram.",
    ),
    SYSML2_INTERNAL_BLOCK("Sysml2InternalBlock", true, "1.0", "SysML 2 internal block diagram."),
    SYSML2_STATE_MACHINE("Sysml2StateMachine", true, "1.0", "SysML 2 state machine diagram."),
    SYSML2_ACTIVITY("Sysml2Activity", true, "1.0", "SysML 2 activity diagram."),
    SYSML2_SEQUENCE("Sysml2Sequence", true, "1.0", "SysML 2 sequence diagram."),
    SYSML2_USE_CASE("Sysml2UseCase", true, "1.0", "SysML 2 use case diagram."),
    SYSML2_REQUIREMENT("Sysml2Requirement", true, "1.0", "SysML 2 requirement diagram."),
    SYSML2_PARAMETRIC("Sysml2Parametric", true, "1.0", "SysML 2 parametric diagram."),

    // BPMN (4) — V3.6.1 / FT-2
    BPMN_PROCESS("BpmnProcess", true, "1.0", "BPMN 2.0 process diagram."),
    BPMN_COLLABORATION("BpmnCollaboration", true, "1.0", "BPMN 2.0 collaboration diagram."),
    BPMN_CHOREOGRAPHY("BpmnChoreography", true, "1.0", "BPMN 2.0 choreography diagram."),
    BPMN_CONVERSATION("BpmnConversation", true, "1.0", "BPMN 2.0 conversation diagram."),

    // Blueprint (1) — V3.6.1 / FT-2
    SERVICE_BLUEPRINT("ServiceBlueprint", true, "1.0", "Service blueprint / user-journey diagram."),
    ;

    public companion object {
        /** Current version of the OKF `type:` vocabulary (see `/okf/kuml-okf-vocabulary.json`). */
        public const val VOCABULARY_VERSION: String = "1.0"

        private val byId = entries.associateBy { it.id }

        /** Resolves a raw `type:` string to an [OkfType]. `null` means unknown/custom — allowed, WARNING only. */
        public fun fromId(id: String?): OkfType? = id?.let { byId[it] }

        /** All types whose documents are expected to carry a ` ```kuml ` block. */
        public val diagramTypes: Set<OkfType> = entries.filter { it.requiresKumlBlock }.toSet()
    }
}
