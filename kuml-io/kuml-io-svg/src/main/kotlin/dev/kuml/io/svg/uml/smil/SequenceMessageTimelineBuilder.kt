package dev.kuml.io.svg.uml.smil

import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.render.smil.SmilAnimation
import dev.kuml.render.smil.SmilTimeline
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlMessage

/**
 * A message dot `<circle>` to inject into the SVG before the closing `</svg>` tag.
 *
 * Each dot is invisible by default (opacity=0) and targeted by SMIL animations.
 * The dot travels horizontally from the source lifeline centre to the target lifeline
 * centre at the message's Y coordinate.
 *
 * Analogous to [dev.kuml.io.svg.bpmn.smil.TokenCircle].
 */
internal data class MessageDot(
    val id: String,
    val color: String,
)

/**
 * Builds a [SmilTimeline] from [TraceFile] [TraceEntry.MessageSent] entries for a
 * UML sequence diagram interaction.
 *
 * ## Coordinate system
 *
 * The builder operates on **padding-shifted** [NodeLayout] coordinates (same as
 * [dev.kuml.io.svg.uml.renderUmlSeqMessages]). The caller ([SequenceSmilRenderer])
 * must apply the same effective-padding logic as [dev.kuml.io.svg.KumlSvgRenderer]'s
 * `renderUmlSequence` before passing `shiftedLayouts`.
 *
 * ## Message-Y formula
 *
 * `y = srcLayout.bounds.origin.y + SEQ_HEAD_HEIGHT + msg.sequence * SEQ_ROW_HEIGHT`
 *
 * Sequence is 1-based, matching the static renderer exactly. Self-messages (`from == to`)
 * are skipped in V3.2 — they have an L-shaped path that cannot be expressed as a straight
 * horizontal `M x,y L x,y`. A future version may animate along the self-call L-path.
 *
 * V3.2 — UML Sequence Diagram SMIL Animation
 */
internal object SequenceMessageTimelineBuilder {
    // MUST stay in sync with the private consts in UmlSequenceSvg.kt.
    private const val SEQ_HEAD_HEIGHT = 40f
    private const val SEQ_ROW_HEIGHT = 32f

    private const val STEP_MS: Long = 1_000L
    private const val OPACITY_DUR_MS: Long = 50L

    /**
     * Builds the animation timeline and list of dot elements to inject.
     *
     * @param interaction The UML interaction (lifelines + messages).
     * @param shiftedLayouts Node layouts already shifted by paddingPx (origin + paddingPx),
     *   keyed by raw NodeId — the same map [dev.kuml.io.svg.uml.renderUmlSeqMessages] consumes.
     * @param trace TraceFile; [TraceEntry.MessageSent] entries drive the dots.
     * @param context Animation tuning parameters.
     * @return Pair of (looped+scaled SmilTimeline, list of MessageDots to inject).
     * @throws IllegalArgumentException if the MessageSent count exceeds [SequenceAnimationContext.MAX_ANIMATIONS].
     */
    fun build(
        interaction: UmlInteraction,
        shiftedLayouts: Map<NodeId, NodeLayout>,
        trace: TraceFile,
        context: SequenceAnimationContext,
    ): Pair<SmilTimeline, List<MessageDot>> {
        val msgById: Map<String, UmlMessage> = interaction.messages.associateBy { it.id }

        val sentEntries =
            trace.entries
                .filterIsInstance<TraceEntry.MessageSent>()
                .sortedBy { it.seqNo }

        require(sentEntries.size <= SequenceAnimationContext.MAX_ANIMATIONS) {
            "Sequence trace has ${sentEntries.size} MessageSent entries which exceeds the " +
                "maximum of ${SequenceAnimationContext.MAX_ANIMATIONS}. Reduce trace length or increase the cap."
        }

        val animations = mutableListOf<SmilAnimation>()
        val dots = mutableListOf<MessageDot>()
        var dotSeq = 0
        var stepIndex = 0

        for (entry in sentEntries) {
            val msg = msgById[entry.messageId]
            val fromId = msg?.fromLifelineId ?: entry.fromLifelineId
            val toId = msg?.toLifelineId ?: entry.toLifelineId

            // Self-messages have an L-shaped path — skip in V3.2.
            // A future version may animate along M cx,y L cx+24,y L cx+24,y+16 L cx,y+16.
            if (fromId == toId) {
                stepIndex++
                continue
            }

            val srcLayout = shiftedLayouts[NodeId(fromId)]
            val tgtLayout = shiftedLayouts[NodeId(toId)]

            if (srcLayout == null || tgtLayout == null) {
                stepIndex++
                continue
            }

            val srcCx = srcLayout.bounds.origin.x + srcLayout.bounds.size.width / 2f
            val tgtCx = tgtLayout.bounds.origin.x + tgtLayout.bounds.size.width / 2f

            // Y formula: headBottom + sequence * rowHeight  (1-based, verbatim from renderUmlMessage)
            // msg == null means the messageId is unknown — we cannot determine the correct Y
            // coordinate, so we skip this entry to avoid placing a dot at the wrong position
            // (seq=0 would incorrectly place the dot at row 0, i.e. the head-box bottom).
            if (msg == null) {
                stepIndex++
                continue
            }
            val msgY = srcLayout.bounds.origin.y + SEQ_HEAD_HEIGHT + msg.sequence * SEQ_ROW_HEIGHT

            val dotId = "kuml-seq-dot-$dotSeq"
            dots += MessageDot(id = dotId, color = context.dotColor)
            dotSeq++

            val beginMs = (stepIndex * STEP_MS)
            val motionDurMs = STEP_MS - OPACITY_DUR_MS * 2
            val pathD = "M ${fmt(srcCx)},${fmt(msgY)} L ${fmt(tgtCx)},${fmt(msgY)}"

            // 1. Fade in
            animations +=
                SmilAnimation.Animate(
                    elementId = dotId,
                    attribute = "opacity",
                    from = "0",
                    to = "1",
                    beginMs = beginMs,
                    durationMs = OPACITY_DUR_MS,
                )
            // 2. Animate motion along horizontal message path
            animations +=
                SmilAnimation.AnimateMotion(
                    elementId = dotId,
                    path = pathD,
                    beginMs = beginMs + OPACITY_DUR_MS,
                    durationMs = motionDurMs,
                )
            // 3. Fade out
            animations +=
                SmilAnimation.Animate(
                    elementId = dotId,
                    attribute = "opacity",
                    from = "1",
                    to = "0",
                    beginMs = beginMs + OPACITY_DUR_MS + motionDurMs,
                    durationMs = OPACITY_DUR_MS,
                )

            stepIndex++
        }

        val rawTimeline = SmilTimeline(animations)

        // Loop the sequence `context.loopCount` times with `context.loopGapMs` pause.
        // LOOP_INFINITE is capped at LOOP_PRACTICAL_MAX to keep SVG size manageable.
        val effectiveLoopCount =
            if (context.loopCount == SequenceAnimationContext.LOOP_INFINITE) {
                SequenceAnimationContext.LOOP_PRACTICAL_MAX
            } else {
                context.loopCount
            }

        // Guard against post-loop expansion exceeding a safe total.
        // MAX_ANIMATIONS caps pre-loop input size, but loopCount multiplies that by up to
        // LOOP_PRACTICAL_MAX (200). Without this check, 500 entries × 200 loops = 100,000
        // SmilAnimation objects (~86 MB heap) and a ~34 MB SMIL fragment per render call.
        // We guard on sentEntries.size (message step count) × effectiveLoopCount, not on
        // rawTimeline.animations.size, because each message step produces 3 SMIL animations
        // (fade-in, motion, fade-out) and the spec threshold (MAX_TOTAL_ANIMATION_STEPS)
        // is defined in terms of message steps, not raw SMIL element count.
        if (sentEntries.isNotEmpty() && effectiveLoopCount > 1) {
            require(sentEntries.size.toLong() * effectiveLoopCount <= SequenceAnimationContext.MAX_TOTAL_ANIMATION_STEPS) {
                "Sequence animation would expand to ${sentEntries.size.toLong() * effectiveLoopCount} " +
                    "message steps after loop tiling (${sentEntries.size} entries × $effectiveLoopCount loops), " +
                    "which exceeds the maximum of ${SequenceAnimationContext.MAX_TOTAL_ANIMATION_STEPS}. " +
                    "Reduce trace length or loopCount."
            }
        }

        val scaledTimeline =
            if (effectiveLoopCount <= 1 || rawTimeline.animations.isEmpty()) {
                rawTimeline.scaledBy(context.speedFactor)
            } else {
                val onePassMs = rawTimeline.totalDurationMs + context.loopGapMs
                val loopedAnimations = rawTimeline.animations.toMutableList()
                for (i in 1 until effectiveLoopCount) {
                    loopedAnimations += rawTimeline.shiftedBy(i * onePassMs).animations
                }
                SmilTimeline(loopedAnimations).scaledBy(context.speedFactor)
            }

        return Pair(scaledTimeline, dots)
    }

    /**
     * Formats a Float as a compact string (integer if exact, otherwise 3 decimal places,
     * always using Locale.US decimal separator).
     *
     * Matches the `fmt` helper in [dev.kuml.io.svg.uml.UmlSequenceSvg] for path consistency.
     */
    private fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) {
            v.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.3f", v)
        }
}
