package dev.kuml.layout.bridge

import dev.kuml.layout.GroupId
import dev.kuml.layout.IgnoredMembership
import dev.kuml.layout.Insets
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.DefaultNoteMeasure
import dev.kuml.layout.bridge.note.NotePlacementConfig
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.Sysml2Model

/**
 * Group membership and note room of a requirement diagram (Nacharbeit 55). The layout bridge (which reserves the room
 * inside the group frames) and the note placement glue (which puts the notes there) both derive their data from here,
 * so they cannot drift apart.
 */
internal object ReqNoteRoom {
    /** Gap between the content of a group and its note strip, and between side by side notes. */
    const val GAP_PX: Float = 12f

    /** Hard-obstacle strips of this width on each side of every group frame line keep notes off the frame. */
    const val BAND_PX: Float = 8f

    /** Which group holds which node (first group in declaration order wins) and the memberships dropped for that. */
    class Membership(
        val groupOfNode: Map<String, GroupId>,
        val ignored: List<IgnoredMembership>,
    )

    /**
     * [liveGroupIds] are the groups the layout actually has (groups without a visible member are dropped by the bridge);
     * [visibleNodeIds] the drawn nodes.
     */
    fun membership(
        diagram: ReqDiagram,
        liveGroupIds: Set<String>,
        visibleNodeIds: Set<String>,
    ): Membership {
        val groupOfNode = LinkedHashMap<String, GroupId>()
        val ignored = ArrayList<IgnoredMembership>()
        for (g in diagram.groups.take(Sysml2LayoutBridge.C1_MAX_GROUPS)) {
            if (g.id !in liveGroupIds) continue
            val gid = GroupId(g.id)
            for (m in g.memberIds.distinct()) {
                if (m !in visibleNodeIds) continue
                val kept = groupOfNode[m]
                if (kept == null) {
                    groupOfNode[m] = gid
                } else if (kept != gid) {
                    ignored += IgnoredMembership(nodeId = NodeId(m), keptGroup = kept, ignoredGroup = gid)
                }
            }
        }
        return Membership(groupOfNode = groupOfNode, ignored = ignored)
    }

    /**
     * The notes owned by exactly one group, per group, in declaration order, with their measured size: every anchor of
     * the note is a node of that group (a note with anchors in two groups, or an anchor that is not a node, is owned by
     * nobody and keeps the general placement).
     */
    fun ownedNotes(
        model: Sysml2Model,
        diagram: ReqDiagram,
        groupOfNode: Map<String, GroupId>,
        visibleNodeIds: Set<String>,
    ): Map<GroupId, List<Pair<String, Size>>> {
        if (model.comments.isEmpty()) return emptyMap()
        val shown = model.notesVisibleIn(diagram = diagram, visibleNodeIds = visibleNodeIds)
        val maxWidth = NotePlacementConfig().maxNoteWidth
        val out = LinkedHashMap<GroupId, MutableList<Pair<String, Size>>>()
        for (c in shown.comments) {
            val targets = model.commentLinks.filter { it.link.noteId == c.id }.map { it.link.targetId }
            if (targets.isEmpty()) continue
            val owners = targets.map { groupOfNode[it] }.distinct()
            val owner = owners.singleOrNull() ?: continue
            val size = DefaultNoteMeasure.measure(body = c.note.body, maxWidth = maxWidth, kind = c.note.kind)
            out.getOrPut(owner) { ArrayList() } += c.id to size
        }
        return out
    }

    /** The strip a group reserves for [notes]: the notes side by side (widths plus gaps), top gap plus the tallest note. */
    fun stripOf(notes: List<Pair<String, Size>>): Size? {
        if (notes.isEmpty()) return null
        val width = notes.sumOf { it.second.width.toDouble() }.toFloat() + GAP_PX * (notes.size - 1)
        return Size(width = width, height = GAP_PX + notes.maxOf { it.second.height })
    }

    /** The strip of a group inside [frame] (layout coordinates): bottom of the frame, above its bottom padding, [notes] side by side. */
    fun home(
        frame: Rect,
        notes: List<Pair<String, Size>>,
        padding: Insets,
    ): Rect? {
        val strip = stripOf(notes) ?: return null
        return Rect(
            origin =
                Point(
                    x = frame.origin.x + padding.left,
                    y = frame.origin.y + frame.size.height - padding.bottom - strip.height,
                ),
            size = strip,
        )
    }
}
