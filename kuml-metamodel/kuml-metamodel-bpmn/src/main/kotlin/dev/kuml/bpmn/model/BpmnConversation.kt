package dev.kuml.bpmn.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Sealed-Hierarchie für Conversation-Knoten (Hexagons) in einem [BpmnConversation].
 *
 * BPMN 2.0 §9.4 unterscheidet drei Arten von Conversation-Knoten:
 * - [ConversationNode]: Einfacher Konversationsknoten (Hexagon mit dünnem Rand).
 * - [CallConversation]: Aufruf einer externen Kollaboration (Hexagon mit dickem Rand).
 * - [SubConversation]: Verschachtelte Konversation mit Kindelementen (Hexagon mit +-Marker).
 *
 * Alle drei müssen mindestens zwei Participants referenzieren ([participants.size >= 2]).
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@Serializable
sealed interface ConversationNodeElement : BpmnElement {
    /** IDs/Namen der beteiligten Participants (mind. 2). */
    val participants: List<String>
}

/**
 * Einfacher BPMN-Konversationsknoten.
 *
 * Repräsentiert eine Konversation zwischen mindestens zwei Participants.
 * Wird als Hexagon mit normalem Rand gerendert.
 *
 * @property id Stabiler Element-Bezeichner.
 * @property name Optionales menschenlesbares Label.
 * @property participants Beteiligte Participants (mind. 2).
 * @property metadata Beliebige zusätzliche Metadaten.
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@Serializable
data class ConversationNode(
    override val id: String,
    override val name: String? = null,
    override val participants: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ConversationNodeElement {
    init {
        require(participants.size >= 2) {
            "A ConversationNode must reference at least two participants " +
                "(got ${participants.size}: $participants)"
        }
    }
}

/**
 * BPMN Call-Conversation — Aufruf einer externen Kollaboration.
 *
 * Referenziert via [calledCollaborationRef] eine externe [BpmnCollaboration].
 * Wird als Hexagon mit dickem Rand gerendert (BPMN-Konvention für Call-Aktivitäten).
 *
 * @property id Stabiler Element-Bezeichner.
 * @property name Optionales menschenlesbares Label.
 * @property participants Beteiligte Participants (mind. 2).
 * @property calledCollaborationRef ID der aufgerufenen externen Kollaboration.
 * @property metadata Beliebige zusätzliche Metadaten.
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@Serializable
data class CallConversation(
    override val id: String,
    override val name: String? = null,
    override val participants: List<String> = emptyList(),
    val calledCollaborationRef: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ConversationNodeElement {
    init {
        require(participants.size >= 2) {
            "A CallConversation must reference at least two participants " +
                "(got ${participants.size}: $participants)"
        }
    }
}

/**
 * BPMN Sub-Conversation — Konversation mit verschachtelten Kindelementen.
 *
 * Enthält [children] als Liste weiterer [ConversationNodeElement]e, was beliebige
 * Verschachtelungstiefe ermöglicht. Wird als Hexagon mit +-Marker gerendert.
 *
 * @property id Stabiler Element-Bezeichner.
 * @property name Optionales menschenlesbares Label.
 * @property participants Beteiligte Participants (mind. 2).
 * @property children Verschachtelte Konversationsknoten.
 * @property metadata Beliebige zusätzliche Metadaten.
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@Serializable
data class SubConversation(
    override val id: String,
    override val name: String? = null,
    override val participants: List<String> = emptyList(),
    val children: List<ConversationNodeElement> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ConversationNodeElement {
    init {
        require(participants.size >= 2) {
            "A SubConversation must reference at least two participants " +
                "(got ${participants.size}: $participants)"
        }
    }

    /** Sucht ein Kindelement rekursiv nach ID. */
    fun childById(id: String): BpmnElement? =
        children.firstOrNull { it.id == id }
            ?: children.filterIsInstance<SubConversation>().firstNotNullOfOrNull { it.childById(id) }
}

/**
 * BPMN Conversation Link — ungerichtete Verbindung zwischen einem Participant und einem Konversationsknoten.
 *
 * BPMN 2.0 §9.5.3: Conversation Links haben keinen Pfeilkopf — sie repräsentieren
 * lediglich die Teilnahme eines Participants an einer Konversation, keine gerichtete
 * Kommunikationsbeziehung.
 *
 * @property id Stabiler Element-Bezeichner.
 * @property name Optionales Label.
 * @property participantRef Name des Participants (muss in [BpmnConversation.participants] enthalten sein).
 * @property conversationNodeRef ID des Konversationsknotens ([ConversationNodeElement]).
 * @property metadata Beliebige zusätzliche Metadaten.
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@Serializable
data class ConversationLink(
    override val id: String,
    override val name: String? = null,
    val participantRef: String,
    val conversationNodeRef: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement

/**
 * BPMN-Konversation — Container für Participants, Konversationsknoten und Links.
 *
 * Entspricht dem `<conversation>` Element im BPMN-2.0-Schema. Participants sind
 * als einfache Namen (Strings) modelliert — keine separate Participant-Klasse,
 * da Conversation-Participants nur Namen tragen, keine Prozess-Referenzen.
 *
 * @property id Stabiler Element-Bezeichner.
 * @property name Optionaler menschenlesbarer Name.
 * @property participants Beteiligte Participants (Namen, müssen eindeutig sein).
 * @property nodes Konversationsknoten (Hexagons).
 * @property links Verbindungen zwischen Participants und Knoten.
 * @property metadata Beliebige zusätzliche Metadaten.
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@Serializable
data class BpmnConversation(
    override val id: String,
    override val name: String? = null,
    val participants: List<String> = emptyList(),
    val nodes: List<ConversationNodeElement> = emptyList(),
    val links: List<ConversationLink> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : BpmnElement {
    init {
        // Guard: participant names must not collide with node IDs.
        // In the layout graph, both are used as NodeIds (Strings), so a collision would cause
        // the renderer to classify a ConversationNode as a Participant rectangle (or vice versa).
        val nodeIds = nodes.map { it.id }.toSet()
        val collisions = participants.filter { it in nodeIds }
        require(collisions.isEmpty()) {
            "BpmnConversation '$id': participant name(s) collide with ConversationNode ID(s): $collisions. " +
                "Participant names and node IDs must be disjoint."
        }
    }

    /**
     * Sucht ein beliebiges Element nach ID — Knoten, Links und rekursive SubConversation-Kinder.
     */
    fun elementById(id: String): BpmnElement? =
        nodes.firstOrNull { it.id == id }
            ?: links.firstOrNull { it.id == id }
            ?: nodes.filterIsInstance<SubConversation>().firstNotNullOfOrNull { it.childById(id) }
}

/**
 * Diagramm-View für eine einzelne [BpmnConversation].
 *
 * Analog zu [ChoreographyDiagram] und [CollaborationDiagram]: referenziert eine
 * Konversation per ID und filtert optional auf eine Teilmenge der Elemente.
 *
 * @property name Diagramm-Name / Titel.
 * @property conversationId ID der [BpmnConversation], die dieses Diagramm visualisiert.
 * @property elementIds IDs der anzuzeigenden Elemente; leer = alle Elemente anzeigen.
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@Serializable
data class ConversationDiagram(
    override val name: String,
    val conversationId: String,
    override val elementIds: List<String> = emptyList(),
) : BpmnDiagram
