package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.BpmnConversation
import dev.kuml.bpmn.model.CallConversation
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ConversationLink
import dev.kuml.bpmn.model.ConversationNode
import dev.kuml.bpmn.model.ConversationNodeElement
import dev.kuml.bpmn.model.SubConversation

/**
 * Builder für eine [BpmnConversation].
 *
 * Instantiiert via [BpmnModelBuilder.conversation]. Erzeugt deterministische IDs
 * `"${conversationId}_node_<n>"` und `"${conversationId}_link_<n>"` und gibt die ID
 * jedes erstellten Elements zurück, damit Aufrufer Conversation Links verdrahten können.
 *
 * Hinweis: [participant] gibt den Namen zurück — dieser Name ist gleichzeitig die
 * Participant-ID in [ConversationLink.participantRef]. Participant-Namen müssen eindeutig
 * innerhalb einer Conversation sein.
 *
 * Beispiel:
 * ```kotlin
 * conversation(id = "conv1", name = "PdV-Kommunikation") {
 *     val mitglied = participant("Mitglied")
 *     val vorstand = participant("Vorstand")
 *     val netzwerk = participant("Netzwerk")
 *     val antrag = node("Mitgliedsantrag", mitglied, vorstand)
 *     val kampagne = node("Wahlkampagne", vorstand, netzwerk)
 *     link(mitglied, antrag)
 *     link(vorstand, antrag)
 *     link(vorstand, kampagne)
 *     link(netzwerk, kampagne)
 * }
 * ```
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@BpmnDsl
class ConversationBuilder(
    private val id: String,
    private val name: String?,
) {
    private val participantNames = mutableListOf<String>()
    private val nodes = mutableListOf<ConversationNodeElement>()
    private val links = mutableListOf<ConversationLink>()
    private var nodeCounter = 0
    private var linkCounter = 0

    /**
     * Deklariert einen Participant dieser Konversation.
     *
     * Der zurückgegebene Name dient als Participant-ID in [link]-Aufrufen.
     * Namen müssen innerhalb einer Conversation eindeutig sein.
     *
     * @param name Menschenlesbarer Name des Participants.
     * @return Der Name des Participants (als stabile ID für [link]-Aufrufe).
     */
    fun participant(name: String): String {
        participantNames += name
        return name
    }

    /**
     * Deklariert einen einfachen Konversationsknoten (Hexagon).
     *
     * @param name Optionales Label des Knotens.
     * @param participants Beteiligte Participants (mind. 2, als vararg).
     * @param id Optionale explizite Knoten-ID; Standard: `"${conversationId}_node_<n>"`.
     * @return Die stabile Knoten-ID (für [link]-Aufrufe).
     */
    fun node(
        name: String? = null,
        vararg participants: String,
        id: String? = null,
    ): String {
        val nid = id ?: "${this.id}_node_${++nodeCounter}"
        nodes += ConversationNode(id = nid, name = name, participants = participants.toList())
        return nid
    }

    /**
     * Deklariert eine Call-Conversation (Hexagon mit dickem Rand).
     *
     * @param name Optionales Label.
     * @param participants Beteiligte Participants (mind. 2, als vararg).
     * @param calledCollaborationRef ID der aufgerufenen externen Kollaboration.
     * @param id Optionale explizite Knoten-ID; Standard: `"${conversationId}_node_<n>"`.
     * @return Die stabile Knoten-ID (für [link]-Aufrufe).
     */
    fun callConversation(
        name: String? = null,
        vararg participants: String,
        calledCollaborationRef: String,
        id: String? = null,
    ): String {
        val nid = id ?: "${this.id}_node_${++nodeCounter}"
        nodes +=
            CallConversation(
                id = nid,
                name = name,
                participants = participants.toList(),
                calledCollaborationRef = calledCollaborationRef,
            )
        return nid
    }

    /**
     * Deklariert eine Sub-Conversation (Hexagon mit +-Marker).
     *
     * @param name Optionales Label.
     * @param participants Beteiligte Participants (mind. 2, als vararg).
     * @param id Optionale explizite Knoten-ID; Standard: `"${conversationId}_node_<n>"`.
     * @param block Konfiguriert Kindelemente via [SubConversationBuilder].
     * @return Die stabile Knoten-ID (für [link]-Aufrufe).
     */
    fun subConversation(
        name: String? = null,
        vararg participants: String,
        id: String? = null,
        block: SubConversationBuilder.() -> Unit = {},
    ): String {
        val nid = id ?: "${this.id}_node_${++nodeCounter}"
        val b = SubConversationBuilder(nid).apply(block)
        nodes +=
            SubConversation(
                id = nid,
                name = name,
                participants = participants.toList(),
                children = b.children.toList(),
            )
        return nid
    }

    /**
     * Deklariert einen Conversation Link (ungerichtete Verbindung).
     *
     * @param participantRef Name des Participants (von [participant] zurückgegeben).
     * @param nodeRef ID des Konversationsknotens (von [node], [callConversation] oder [subConversation]).
     * @param name Optionales Label des Links.
     * @return Die stabile Link-ID.
     */
    fun link(
        participantRef: String,
        nodeRef: String,
        name: String? = null,
    ): String {
        val lid = "${this.id}_link_${++linkCounter}"
        links +=
            ConversationLink(
                id = lid,
                name = name,
                participantRef = participantRef,
                conversationNodeRef = nodeRef,
            )
        return lid
    }

    internal fun build(): BpmnConversation =
        BpmnConversation(
            id = id,
            name = name,
            participants = participantNames.toList(),
            nodes = nodes.toList(),
            links = links.toList(),
        )
}

/**
 * Builder für Kindelemente einer [SubConversation].
 *
 * Instantiiert via [ConversationBuilder.subConversation].
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@BpmnDsl
class SubConversationBuilder(
    private val id: String,
) {
    internal val children = mutableListOf<ConversationNodeElement>()
    private var counter = 0

    /**
     * Deklariert einen einfachen Konversationsknoten als Kind dieser Sub-Conversation.
     *
     * @param name Optionales Label.
     * @param participants Beteiligte Participants (mind. 2, als vararg).
     * @param id Optionale explizite Knoten-ID.
     * @return Die stabile Knoten-ID.
     */
    fun node(
        name: String? = null,
        vararg participants: String,
        id: String? = null,
    ): String {
        val nid = id ?: "${this.id}_child_${++counter}"
        children += ConversationNode(id = nid, name = name, participants = participants.toList())
        return nid
    }

    /**
     * Deklariert eine Call-Conversation als Kind dieser Sub-Conversation.
     *
     * @param name Optionales Label.
     * @param participants Beteiligte Participants (mind. 2, als vararg).
     * @param calledCollaborationRef ID der aufgerufenen externen Kollaboration.
     * @param id Optionale explizite Knoten-ID.
     * @return Die stabile Knoten-ID.
     */
    fun callConversation(
        name: String? = null,
        vararg participants: String,
        calledCollaborationRef: String,
        id: String? = null,
    ): String {
        val nid = id ?: "${this.id}_child_${++counter}"
        children +=
            CallConversation(
                id = nid,
                name = name,
                participants = participants.toList(),
                calledCollaborationRef = calledCollaborationRef,
            )
        return nid
    }

    /**
     * Deklariert eine verschachtelte Sub-Conversation als Kind.
     *
     * @param name Optionales Label.
     * @param participants Beteiligte Participants (mind. 2, als vararg).
     * @param id Optionale explizite Knoten-ID.
     * @param block Konfiguriert weitere Kindelemente via [SubConversationBuilder].
     * @return Die stabile Knoten-ID.
     */
    fun subConversation(
        name: String? = null,
        vararg participants: String,
        id: String? = null,
        block: SubConversationBuilder.() -> Unit = {},
    ): String {
        val nid = id ?: "${this.id}_child_${++counter}"
        val b = SubConversationBuilder(nid).apply(block)
        children +=
            SubConversation(
                id = nid,
                name = name,
                participants = participants.toList(),
                children = b.children.toList(),
            )
        return nid
    }
}

/**
 * Builder für ein [ConversationDiagram].
 *
 * Instantiiert via [BpmnModelBuilder.conversationDiagram].
 *
 * Beispiel:
 * ```kotlin
 * conversationDiagram("PdV-Übersicht", conversationId = "conv1")
 * ```
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
@BpmnDsl
class ConversationDiagramBuilder(
    private val name: String,
    private val conversationId: String,
) {
    private val elementIds = mutableListOf<String>()

    /**
     * Schränkt das Diagramm auf bestimmte Elemente ein.
     *
     * Wenn keine IDs eingeschlossen werden, zeigt das Diagramm alle Elemente
     * der referenzierten Conversation.
     *
     * @param ids Element-IDs, die im Diagramm angezeigt werden.
     */
    fun include(vararg ids: String) {
        elementIds += ids.toList()
    }

    internal fun build(): ConversationDiagram =
        ConversationDiagram(
            name = name,
            conversationId = conversationId,
            elementIds = elementIds.toList(),
        )
}
