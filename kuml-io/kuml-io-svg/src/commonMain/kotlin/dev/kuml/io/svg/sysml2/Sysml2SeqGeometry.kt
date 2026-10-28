package dev.kuml.io.svg.sysml2

import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.InteractionUseUsage
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
) {
    /** Cumulative vertical offset (px) of the row [seqNo]. */
    fun offsetAt(seqNo: Int): Float =
        SYSML2_FRAGMENT_HEADER_BAND * bandStartSeqs.count { it <= seqNo } +
            SYSML2_REF_EXTRA_HEIGHT * refSeqs.count { it < seqNo }

    internal companion object {
        val NONE: SeqRowOffsets = SeqRowOffsets(bandStartSeqs = emptyList(), refSeqs = emptyList())

        fun of(
            fragments: List<CombinedFragmentUsage>,
            refs: List<InteractionUseUsage>,
        ): SeqRowOffsets =
            SeqRowOffsets(
                bandStartSeqs = fragments.flatMap { it.operands }.map { it.startSeqNo }.sorted(),
                refSeqs = refs.map { it.seqNo }.sorted(),
            )
    }
}

/** A `ref` frame is [SYSML2_REF_HEIGHT] high, i.e. this much more than a normal 32 px row. */
internal const val SYSML2_REF_EXTRA_HEIGHT: Float = 16f

/** Height of a `ref` frame. */
internal const val SYSML2_REF_HEIGHT: Float = 48f

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
