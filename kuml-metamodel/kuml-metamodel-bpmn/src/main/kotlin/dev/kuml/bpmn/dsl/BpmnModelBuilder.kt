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
    private val diagramBuilders: MutableList<ProcessDiagramBuilder> = mutableListOf()
    private val dataStoreList: MutableList<BpmnDataStore> = mutableListOf()
    private var dataStoreCounter: Int = 0

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

    fun build(): BpmnModel =
        BpmnModel(
            name = name,
            processes = processBuilders.map { it.build() },
            dataStores = dataStoreList.toList(),
            diagrams = diagramBuilders.map { it.build() },
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
