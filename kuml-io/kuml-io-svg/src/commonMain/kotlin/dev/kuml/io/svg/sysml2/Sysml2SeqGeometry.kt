package dev.kuml.io.svg.sysml2

import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.InteractionUseUsage
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.Sysml2Limits

/**
 * Vertical row model of a SysML 2 sequence diagram (C3).
 *
 * Every combined-fragment operand reserves a [SYSML2_FRAGMENT_HEADER_BAND] above its first row (nested
 * operands included, so inner fragment heads get their own band); every `ref` frame is 48 px high
 * instead of the 32 px row and therefore pushes all following rows down by [SYSML2_REF_EXTRA_HEIGHT].
 * One instance is shared by messages, execution bars, fragment frames and the new C3 constructs so
 * that all of them agree on the y of a row.
 */
internal class SeqRowOffsets(
    private val bandStartSeqs: List<Int>,
    private val refSeqs: List<Int>,
    /** Extra vertical room opened after a row: (row, px) — rows strictly after `row` move down by `px`. */
    private val gaps: List<Pair<Int, Float>> = emptyList(),
) {
    /** Cumulative vertical offset (px) of the row [seqNo]. */
    fun offsetAt(seqNo: Int): Float =
        SYSML2_FRAGMENT_HEADER_BAND * bandStartSeqs.count { it <= seqNo } +
            SYSML2_REF_EXTRA_HEIGHT * refSeqs.count { it < seqNo } +
            gaps.filter { it.first < seqNo }.sumOf { it.second.toDouble() }.toFloat()

    internal companion object {
        val NONE: SeqRowOffsets = SeqRowOffsets(bandStartSeqs = emptyList(), refSeqs = emptyList())

        /** Minimum distance (px) between the end of one fragment and the frame of the next one (wave 2F). */
        const val FRAGMENT_GAP_PX: Float = 12f

        /** Frame bottom of a fragment is 8 px below its last content; the next frame's top lies 11 px below a plain row. */
        private const val NATURAL_GAP_PX: Float = 11f

        fun of(
            fragments: List<CombinedFragmentUsage>,
            refs: List<InteractionUseUsage>,
            messages: List<MessageUsage> = emptyList(),
        ): SeqRowOffsets {
            val gaps = ArrayList<Pair<Int, Float>>()
            val selfRows =
                messages
                    .filter {
                        it.sourceLifelineId == it.targetLifelineId &&
                            it.kind != MessageKind.Found &&
                            it.kind != MessageKind.Lost
                    }.map { it.seqNo }
                    .toSet()
            val messageSeqNos = messages.map { it.seqNo }.toSet()
            val tree = SeqFragmentTree(fragments)
            val ends = HashMap<Int, Float>()
            for (f in fragments) {
                if (f.operands.isEmpty()) continue
                val end = f.operands.maxOf { it.endSeqNo }
                // content in the last row hangs below the row line: a self loop, or half of a `ref` frame
                val reach =
                    maxOf(
                        if (end in selfRows) SYSML2_SELF_CALL_HEIGHT else 0f,
                        if (refs.any { it.seqNo == end }) SYSML2_REF_HEIGHT / 2f else 0f,
                    )
                var need = if (end in selfRows) 8f else 0f
                val next = fragments.filter { it.id != f.id && it.operands.minOfOrNull { o -> o.startSeqNo } == end + 1 }
                val separate =
                    next.any { n ->
                        n.id !in tree.selfAndDescendants(f.id).map { it.id } && f.id !in tree.selfAndDescendants(n.id).map { it.id }
                    }
                if (separate) {
                    val nestedStep = SYSML2_FRAGMENT_BOTTOM_STEP * (tree.places[f.id]?.height ?: 0).coerceAtMost(2)
                    need = maxOf(need, reach + FRAGMENT_GAP_PX - NATURAL_GAP_PX + nestedStep)
                }
                if (need > 0f) ends[end] = maxOf(ends[end] ?: 0f, need)
            }
            // a self loop right before the first row of a fragment must not touch its frame (frame top is 19 px below the row)
            for (row in selfRows) {
                val fragmentStartsNext = fragments.any { f -> f.operands.minOfOrNull { it.startSeqNo } == row + 1 }
                if (fragmentStartsNext && (ends[row] ?: 0f) < 9f) ends[row] = 9f
                // a plain message right after the loop: its label background must not cover the arrowhead
                if (!fragmentStartsNext && row + 1 in messageSeqNos) {
                    ends[row] = maxOf(ends[row] ?: 0f, SYSML2_SELF_CALL_FOLLOW_GAP)
                }
            }
            for ((row, px) in ends) gaps += row to px
            return SeqRowOffsets(
                bandStartSeqs = fragments.flatMap { it.operands }.map { it.startSeqNo }.sorted(),
                refSeqs = refs.map { it.seqNo }.sorted(),
                gaps = gaps.sortedBy { it.first },
            )
        }
    }
}

/** A `ref` frame is [SYSML2_REF_HEIGHT] high (wave 2F: as high as its text plus 12 px, so no longer taller than a row). */
internal const val SYSML2_REF_EXTRA_HEIGHT: Float = 0f

/** Height of a `ref` frame. */
internal const val SYSML2_REF_HEIGHT: Float = 28f

/** Height of the loop of a self message (wave 2F). */
internal const val SYSML2_SELF_CALL_HEIGHT: Float = 20f

/** Clearance (px) below a self-call loop so that the label of the next message does not cover its arrowhead. */
internal const val SYSML2_SELF_CALL_FOLLOW_GAP: Float = 12f

/** Horizontal inset per nesting level of a combined fragment. */
internal const val SYSML2_FRAGMENT_INDENT: Float = 10f

/** Vertical distance a nested frame's bottom edge is moved inwards per level below the parent. */
internal const val SYSML2_FRAGMENT_BOTTOM_STEP: Float = 6f

/** Placement info of one fragment inside its nesting tree. */
internal data class SeqFragmentPlace(
    val depth: Int,
    /** Number of levels below this fragment (0 for a leaf). */
    val height: Int,
    val rootId: String,
)

/**
 * Validated nesting tree of the combined fragments of a model (C3).
 *
 * A fragment whose parent is unknown, self-referencing, part of a cycle or deeper than
 * [Sysml2Limits.FRAGMENT_DEPTH] is treated as a top-level (flat) fragment — the renderer never fails on
 * malformed nesting; the checker reports it. All traversals are iterative and bounded.
 */
internal class SeqFragmentTree(
    fragments: List<CombinedFragmentUsage>,
) {
    private val byId: Map<String, CombinedFragmentUsage> = fragments.associateBy { it.id }
    private val parentOf: Map<String, String> = validatedParents(fragments)

    val places: Map<String, SeqFragmentPlace>
    private val childrenOf: Map<String, List<CombinedFragmentUsage>>
    private val leftPadFull: Map<String, Float>

    init {
        val children = LinkedHashMap<String, MutableList<CombinedFragmentUsage>>()
        for (f in fragments) parentOf[f.id]?.let { children.getOrPut(it) { mutableListOf() }.add(f) }
        childrenOf = children

        val depth = HashMap<String, Int>()
        val root = HashMap<String, String>()
        for (f in fragments) {
            var d = 0
            var cur = f.id
            while (true) {
                val p = parentOf[cur] ?: break
                cur = p
                d++
            }
            depth[f.id] = d
            root[f.id] = cur
        }
        val height = HashMap<String, Int>()
        val pad = HashMap<String, Float>()
        for (f in fragments) {
            height[f.id] = 0
            pad[f.id] = sysml2SeqFragmentLeftPad(f)
        }
        for (f in fragments.sortedByDescending { depth.getValue(it.id) }) {
            val p = parentOf[f.id] ?: continue
            height[p] = maxOf(height.getValue(p), height.getValue(f.id) + 1)
            pad[p] = maxOf(pad.getValue(p), pad.getValue(f.id) + SYSML2_FRAGMENT_INDENT)
        }
        places =
            fragments.associate {
                it.id to
                    SeqFragmentPlace(depth = depth.getValue(it.id), height = height.getValue(it.id), rootId = root.getValue(it.id))
            }
        leftPadFull = pad
    }

    /** Left padding the *outermost* frame of the tree containing [id] needs (own guard and all nested guards). */
    fun rootLeftPad(id: String): Float = leftPadFull.getValue(places.getValue(id).rootId)

    fun heightOfRoot(id: String): Int = places.getValue(id).let { places.getValue(it.rootId).height }

    /** [id] and all fragments nested below it. */
    fun selfAndDescendants(id: String): List<CombinedFragmentUsage> {
        val result = ArrayList<CombinedFragmentUsage>()
        val stack = ArrayDeque<String>()
        stack.addLast(id)
        val seen = HashSet<String>()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!seen.add(cur)) continue
            byId[cur]?.let { result.add(it) }
            childrenOf[cur]?.forEach { stack.addLast(it.id) }
        }
        return result
    }

    private companion object {
        fun validatedParents(fragments: List<CombinedFragmentUsage>): Map<String, String> {
            val byId = fragments.associateBy { it.id }
            val candidate = HashMap<String, String>()
            for (f in fragments) {
                val p = f.parentFragmentId ?: continue
                if (p != f.id && p in byId) candidate[f.id] = p
            }
            // Drop links that sit in a cycle or lead to a chain deeper than the limit.
            val valid = HashMap<String, String>()
            for (f in fragments) {
                val parent = candidate[f.id] ?: continue
                var steps = 0
                var cur: String? = parent
                var ok = true
                val seen = HashSet<String>()
                seen.add(f.id)
                while (cur != null) {
                    if (!seen.add(cur) || ++steps >= Sysml2Limits.FRAGMENT_DEPTH) {
                        ok = false
                        break
                    }
                    cur = candidate[cur]
                }
                if (ok) valid[f.id] = parent
            }
            return valid
        }
    }
}

/** Clips [text] to [max] characters with an ellipsis; texts are always rendered through the escaping text builder. */
internal fun clipSeqText(
    text: String,
    max: Int = Sysml2Limits.TEXT,
): String = if (text.length <= max) text else text.take(max - 1) + "…"
