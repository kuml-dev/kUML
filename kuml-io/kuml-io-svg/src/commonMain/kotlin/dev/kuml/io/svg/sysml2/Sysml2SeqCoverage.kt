package dev.kuml.io.svg.sysml2

import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.DurationConstraintUsage
import dev.kuml.sysml2.InteractionUseUsage
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.sysml2.TimeObservationUsage

/**
 * Lifelines a combined fragment really covers (PAL wave 2F, S1): the union of source and target of all messages in
 * the row range of its operands (found / lost / create / destroy included), of the lifelines of `ref` frames, time
 * observations and duration constraints in that range, and, recursively, of its nested fragments. An empty result
 * means "unknown": the caller then falls back to the full width.
 */
internal fun coveredLifelines(
    fragment: CombinedFragmentUsage,
    usages: List<Sysml2Usage>,
    tree: SeqFragmentTree,
): Set<String> {
    val out = LinkedHashSet<String>()
    for (f in tree.selfAndDescendants(fragment.id)) {
        if (f.operands.isEmpty()) continue
        val lo = f.operands.minOf { it.startSeqNo }
        val hi = f.operands.maxOf { it.endSeqNo }
        for (u in usages) {
            when (u) {
                is MessageUsage ->
                    if (u.seqNo in lo..hi) {
                        out += u.sourceLifelineId
                        out += u.targetLifelineId
                    }
                is InteractionUseUsage -> if (u.seqNo in lo..hi) out += u.lifelineIds
                is TimeObservationUsage -> if (u.seqNo in lo..hi) out += u.lifelineId
                is DurationConstraintUsage ->
                    if (minOf(u.fromSeqNo, u.toSeqNo) >= lo &&
                        maxOf(u.fromSeqNo, u.toSeqNo) <= hi
                    ) {
                        out += u.lifelineId
                    }
                else -> Unit
            }
        }
    }
    return out
}
