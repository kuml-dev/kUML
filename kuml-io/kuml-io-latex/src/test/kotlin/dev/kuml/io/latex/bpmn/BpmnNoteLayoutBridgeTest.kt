package dev.kuml.io.latex.bpmn

import dev.kuml.io.latex.note.NoteFixtures
import dev.kuml.layout.GroupId
import dev.kuml.layout.NodeId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BpmnNoteLayoutBridgeTest :
    StringSpec({
        "the synthetic layout carries the pool and lane geometry and the flow node boxes of the plan" {
            val (m, _) = NoteFixtures.bpmnCollaborationModel()
            val collab = m.collaborations.single()
            val plan = BpmnCollaborationLayout.plan(model = m, collab = collab)
            val layout = BpmnNoteLayoutBridge.syntheticLayout(plan = plan, obstacles = emptyMap(), hinted = emptyList())
            val pool = plan.pools.first()
            val g = layout.groups.getValue(GroupId("poolA")).bounds
            g.origin.x shouldBe 0f
            g.origin.y shouldBe pool.top
            g.size.width shouldBe plan.width
            g.size.height shouldBe pool.height
            val lane = pool.lanes.single()
            layout.groups
                .getValue(GroupId("laneA"))
                .bounds.origin.y shouldBe pool.top + lane.top
            val placed = pool.nodes.single()
            val b = layout.nodes.getValue(NodeId("tA")).bounds
            (b.origin.x + b.size.width / 2f) shouldBe placed.x
            (b.origin.y + b.size.height / 2f) shouldBe pool.top + placed.y
        }
    })
