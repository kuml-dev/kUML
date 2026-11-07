@file:Suppress("RequireNamedArguments")

package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions

/** Collaboration models for the BPMN LaTeX tests: real processes inside pools, lanes and message flows. */
internal object BpmnCollaborationFixtures {
    fun start(
        id: String,
        name: String? = null,
    ) = BpmnEvent(
        id = id,
        name = name,
        position = EventPosition.START,
        definition = EventDefinition.NONE,
        behaviour = EventBehaviour.CATCHING,
    )

    fun end(
        id: String,
        name: String? = null,
    ) = BpmnEvent(
        id = id,
        name = name,
        position = EventPosition.END,
        definition = EventDefinition.NONE,
        behaviour = EventBehaviour.THROWING,
    )

    fun task(
        id: String,
        name: String? = null,
    ) = BpmnTask(id = id, name = name)

    /** A chain of the given nodes, joined by sequence flows. */
    fun chain(
        id: String,
        name: String?,
        nodes: List<BpmnFlowNode>,
    ) = BpmnProcess(
        id = id,
        name = name,
        flowNodes = nodes,
        sequenceFlows = nodes.zipWithNext().mapIndexed { i, (a, b) -> SequenceFlow(id = "${id}_f$i", sourceRef = a.id, targetRef = b.id) },
    )

    fun model(
        processes: List<BpmnProcess>,
        participants: List<BpmnParticipant>,
        messageFlows: List<MessageFlow> = emptyList(),
        name: String = "Collab model",
    ): BpmnModel =
        BpmnModel(
            name = name,
            processes = processes,
            collaborations = listOf(BpmnCollaboration(id = "c1", name = "C", participants = participants, messageFlows = messageFlows)),
            diagrams = listOf(CollaborationDiagram(name = "D", collaborationId = "c1")),
        )

    fun toLatex(
        model: BpmnModel,
        standalone: Boolean = false,
    ): String = KumlLatexRenderer.toLatex(model = model, options = LatexRenderOptions(standalone = standalone))

    /** Customer / Supplier (nested lanes) / black-box carrier, with message flows into, out of and between the pools. */
    fun orderFlow(): BpmnModel {
        val customer =
            chain(
                id = "cust",
                name = "Customer",
                nodes = listOf(start("c_start", "Place Order"), task("c_wait", "Wait for Shipment"), end("c_end", "Done")),
            )
        val supplier =
            chain(
                id = "supp",
                name = "Supplier",
                nodes =
                    listOf(
                        start("s_start", "Receive Order"),
                        task("s_pick", "Pick and Pack"),
                        task("s_ship", "Ship Goods"),
                        end("s_end", "Goods Shipped"),
                    ),
            )
        return model(
            processes = listOf(customer, supplier),
            participants =
                listOf(
                    BpmnParticipant(id = "pool_c", name = "Customer", processRef = "cust"),
                    BpmnParticipant(
                        id = "pool_s",
                        name = "Supplier",
                        processRef = "supp",
                        lanes =
                            listOf(
                                BpmnLane(
                                    id = "lane_office",
                                    name = "Office",
                                    childLanes =
                                        listOf(
                                            BpmnLane(id = "lane_sales", name = "Sales", flowNodeRefs = listOf("s_start")),
                                            BpmnLane(id = "lane_buy", name = "Purchasing", flowNodeRefs = listOf("s_pick")),
                                        ),
                                ),
                                BpmnLane(id = "lane_logistics", name = "Logistics", flowNodeRefs = listOf("s_ship", "s_end")),
                            ),
                    ),
                    BpmnParticipant(id = "pool_x", name = "Carrier"),
                ),
            messageFlows =
                listOf(
                    MessageFlow(id = "m1", name = "Purchase Order", sourceRef = "c_start", targetRef = "s_start"),
                    MessageFlow(id = "m2", name = "Shipping Notice", sourceRef = "s_end", targetRef = "c_wait"),
                    MessageFlow(id = "m3", name = "Pickup Request", sourceRef = "s_ship", targetRef = "pool_x"),
                    MessageFlow(id = "m4", name = "Proof of Delivery", sourceRef = "pool_x", targetRef = "s_end"),
                ),
        )
    }
}
