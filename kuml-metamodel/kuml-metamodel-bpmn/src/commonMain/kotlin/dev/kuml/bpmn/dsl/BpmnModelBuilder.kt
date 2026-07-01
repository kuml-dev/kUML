package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.BpmnDataStore
import dev.kuml.bpmn.model.BpmnModel

/**
 * Top-level builder for a [BpmnModel].
 *
 * Collects processes and diagram views as the user populates the model block.
 * The recommended entry point is the [bpmnModel] top-level function which mirrors
 * the existing `sysml2Model(...)` / `c4Model(...)` shape so BPMN scripts look
 * at-home next to existing kUML scripts.
 *
 * Example:
 * ```kotlin
 * val model = bpmnModel("Order Management") {
 *     process(id = "orderProcess", name = "Order Process") {
 *         val start = startEvent("Order received")
 *         val review = task("Review Order", type = TaskType.USER)
 *         val end   = endEvent("Order processed")
 *         start flowsTo review flowsTo end
 *     }
 *     diagram("Happy Path", processId = "orderProcess")
 * }
 * ```
 */
@BpmnDsl
class BpmnModelBuilder(
    private val name: String,
) {
    private val processBuilders: MutableList<ProcessBuilder> = mutableListOf()
    private val diagramBuilders: MutableList<Any> = mutableListOf()
    private val dataStoreList: MutableList<BpmnDataStore> = mutableListOf()
    private var dataStoreCounter: Int = 0
    private val collaborationBuilders: MutableList<CollaborationBuilder> = mutableListOf()
    private val choreographyBuilders: MutableList<ChoreographyBuilder> = mutableListOf()
    private val conversationBuilders: MutableList<ConversationBuilder> = mutableListOf()

    /**
     * Declare a data store at root (model) level.
     *
     * In BPMN 2.0, [BpmnDataStore] is a RootElement that belongs to the Definitions container,
     * not to any particular process. Use this method to declare data stores that can be
     * referenced from multiple processes.
     *
     * @param id Optional explicit data store id; defaults to `"dataStore_<n>"` where `<n>` is the 1-based position.
     * @param name Optional human-readable data store name.
     * @param unlimited When `true`, the data store has unlimited capacity.
     * @return The stable ID of the created data store (for use in [dataAssociation] calls).
     */
    fun dataStore(
        id: String? = null,
        name: String? = null,
        unlimited: Boolean = false,
    ): String {
        dataStoreCounter++
        val dsId = id ?: "dataStore_$dataStoreCounter"
        dataStoreList += BpmnDataStore(id = dsId, name = name, unlimited = unlimited)
        return dsId
    }

    /**
     * Declare a process.
     *
     * @param id Optional explicit process id; defaults to `"process_<n>"` where `<n>` is the 1-based position.
     * @param name Optional human-readable process name.
     * @param block Block that populates the process via [ProcessBuilder].
     */
    fun process(
        id: String? = null,
        name: String? = null,
        block: ProcessBuilder.() -> Unit,
    ) {
        val processId = id ?: "process_${processBuilders.size + 1}"
        processBuilders += ProcessBuilder(id = processId, name = name).apply(block)
    }

    /**
     * Declare a diagram view scoped to a single process.
     *
     * @param name Human-readable diagram name.
     * @param processId ID of the process this diagram visualises.
     * @param block Optional block that restricts the visible elements via [ProcessDiagramBuilder.include].
     */
    fun diagram(
        name: String,
        processId: String,
        block: ProcessDiagramBuilder.() -> Unit = {},
    ) {
        diagramBuilders += ProcessDiagramBuilder(name = name, processId = processId).apply(block)
    }

    /**
     * Declares a collaboration (multi-pool container with message flows).
     *
     * @param name Optional human-readable collaboration name.
     * @param id Optional explicit collaboration ID; defaults to `"collab_<n>"`.
     * @param block Block to configure pools and message flows via [CollaborationBuilder].
     */
    fun collaboration(
        name: String? = null,
        id: String? = null,
        block: CollaborationBuilder.() -> Unit,
    ) {
        val cid = id ?: "collab_${collaborationBuilders.size + 1}"
        collaborationBuilders += CollaborationBuilder(cid, name).apply(block)
    }

    /**
     * Declares a diagram view scoped to a single collaboration.
     *
     * @param name Human-readable diagram name.
     * @param collaborationId ID of the collaboration this diagram visualises.
     * @param block Optional block that restricts the visible elements via
     *   [CollaborationDiagramBuilder.include].
     */
    fun collaborationDiagram(
        name: String,
        collaborationId: String,
        block: CollaborationDiagramBuilder.() -> Unit = {},
    ) {
        diagramBuilders += CollaborationDiagramBuilder(name = name, collaborationId = collaborationId).apply(block)
    }

    /**
     * Declares a choreography (sequence of two-party interactions without internal process logic).
     *
     * @param id Optional explicit choreography ID; defaults to `"choreography_<n>"`.
     * @param name Optional human-readable choreography name.
     * @param block Block to configure tasks, gateways, events, and sequence flows via
     *   [ChoreographyBuilder].
     * @return The stable choreography ID (use for [choreographyDiagram] references).
     */
    fun choreography(
        id: String? = null,
        name: String? = null,
        block: ChoreographyBuilder.() -> Unit,
    ): String {
        val cid = id ?: "choreography_${choreographyBuilders.size + 1}"
        choreographyBuilders += ChoreographyBuilder(cid, name).apply(block)
        return cid
    }

    /**
     * Declares a diagram view scoped to a single choreography.
     *
     * @param name Human-readable diagram name.
     * @param choreographyId ID of the choreography this diagram visualises.
     * @param block Optional block that restricts the visible elements via
     *   [ChoreographyDiagramBuilder.include].
     */
    fun choreographyDiagram(
        name: String,
        choreographyId: String,
        block: ChoreographyDiagramBuilder.() -> Unit = {},
    ) {
        diagramBuilders += ChoreographyDiagramBuilder(name = name, choreographyId = choreographyId).apply(block)
    }

    /**
     * Declares a conversation (simplified interaction view without internal process logic).
     *
     * @param id Optional explicit conversation ID; defaults to `"conversation_<n>"`.
     * @param name Optional human-readable conversation name.
     * @param block Block to configure participants, nodes, and links via [ConversationBuilder].
     * @return The stable conversation ID (use for [conversationDiagram] references).
     */
    fun conversation(
        id: String? = null,
        name: String? = null,
        block: ConversationBuilder.() -> Unit,
    ): String {
        val cid = id ?: "conversation_${conversationBuilders.size + 1}"
        conversationBuilders += ConversationBuilder(cid, name).apply(block)
        return cid
    }

    /**
     * Declares a diagram view scoped to a single conversation.
     *
     * @param name Human-readable diagram name.
     * @param conversationId ID of the conversation this diagram visualises.
     * @param block Optional block that restricts the visible elements via
     *   [ConversationDiagramBuilder.include].
     */
    fun conversationDiagram(
        name: String,
        conversationId: String,
        block: ConversationDiagramBuilder.() -> Unit = {},
    ) {
        diagramBuilders += ConversationDiagramBuilder(name = name, conversationId = conversationId).apply(block)
    }

    fun build(): BpmnModel =
        BpmnModel(
            name = name,
            processes = processBuilders.map { it.build() },
            dataStores = dataStoreList.toList(),
            collaborations = collaborationBuilders.map { it.build() },
            choreographies = choreographyBuilders.map { it.build() },
            conversations = conversationBuilders.map { it.build() },
            diagrams =
                diagramBuilders.map { builder ->
                    when (builder) {
                        is ProcessDiagramBuilder -> builder.build()
                        is CollaborationDiagramBuilder -> builder.build()
                        is ChoreographyDiagramBuilder -> builder.build()
                        is ConversationDiagramBuilder -> builder.build()
                        else -> error("Unknown diagram builder type: ${builder::class}")
                    }
                },
        )
}

/**
 * Top-level entry point — `bpmnModel("Order Management") { … }`.
 *
 * Mirrors the existing `sysml2Model(...)` / `c4Model(...)` shape so BPMN
 * scripts look at-home next to existing kUML scripts. The DSL produces a
 * fully-built [BpmnModel] — no half-states, no resolver phase.
 *
 * @param name Human-readable model name.
 * @param block Block that populates the model via [BpmnModelBuilder].
 * @return A fully constructed [BpmnModel].
 */
fun bpmnModel(
    name: String,
    block: BpmnModelBuilder.() -> Unit,
): BpmnModel = BpmnModelBuilder(name).apply(block).build()
