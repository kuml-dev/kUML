package dev.kuml.layout.bridge.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.SizeProvider

/**
 * Content-aware [SizeProvider] for BPMN process/collaboration/conversation diagrams.
 *
 * The bridge's default sizing ([BpmnLayoutBridge.DEFAULT_TASK_SIZE], 120×60) is a fixed
 * box regardless of label length. The SVG renderer draws a single centered `<text>` for
 * the activity label without measurement or wrapping, so anything longer than a handful
 * of characters overflows the box on both sides — visible with realistic German BPMN
 * labels such as "Schriftlichen Aufnahmeantrag stellen".
 *
 * This provider walks the model once and pre-computes an `elementId → Size` map for every
 * [BpmnTask], collapsed/expanded [BpmnSubProcess] and [BpmnCallActivity] (recursing into
 * expanded sub-processes' [BpmnSubProcess.flowElementNodes], because the bridge emits
 * those inner children as grouped nodes and calls [sizeOf] for them too). Width grows with
 * the label length using a fixed per-character estimate (no real font metrics — consistent
 * with [dev.kuml.layout.bridge.UmlContentSizeProvider]), floored at the existing 120 px
 * default and capped at [MAX_TASK_WIDTH]. Height stays fixed at 60 px — single-line
 * labels, no word-wrap, matching the renderer's centered single `<text>` element.
 *
 * For an **expanded** [BpmnSubProcess], the entry computed here is not used as a node
 * box size (the frame has no phantom [dev.kuml.layout.LayoutNode] — see
 * [BpmnLayoutBridge.toLayoutGraph]'s "NOTE: No phantom node…" comment). Instead
 * [BpmnLayoutBridge] reads it back via [sizeOf] and passes it as
 * [dev.kuml.layout.LayoutGroup.minSize], which floors the frame's final width/height
 * so the frame's own centred title (rendered by `renderBpmnSubProcess` the same way as
 * a collapsed box's label) fits inside the border even when the child nodes alone
 * would lay out narrower than the title. The engine-side ELK bridge enforces this
 * floor *after* layout (`ResultMapper.buildGroupLayouts` in `kuml-layout-elk`) rather
 * than via ELK's own node-size-constraint properties, which have no effect on a
 * hierarchical/compound node's own size — see that function's KDoc.
 *
 * All other BPMN element kinds (gateways, events, data objects, choreography/conversation
 * nodes) are untouched: their labels render *outside* the shape in the SVG renderer, so
 * widening the shape would not help and could distort the diagram. Because a non-null
 * [SizeProvider] makes the bridge's own `?: defaultSize` fallback unreachable for every
 * call site, [defaultForKind] reproduces the exact same public default sizes the bridge
 * would otherwise have used, so passing this provider is a no-op for those kinds.
 *
 * Usage:
 * ```kotlin
 * val graph = BpmnLayoutBridge.toLayoutGraph(model, diagram, BpmnContentSizeProvider(model))
 * ```
 */
public class BpmnContentSizeProvider(
    model: BpmnModel,
) : SizeProvider {
    private val byId: Map<String, Size> =
        run {
            val out = mutableMapOf<String, Size>()
            for (process in model.processes) collect(process.flowNodes, out)
            out
        }

    override fun sizeOf(
        elementId: String,
        elementKind: String,
    ): Size = byId[elementId] ?: defaultForKind(elementKind)

    private fun collect(
        nodes: Iterable<BpmnFlowNode>,
        out: MutableMap<String, Size>,
    ) {
        for (node in nodes) {
            when (node) {
                is BpmnTask -> out[node.id] = taskBoxSize(node.name)
                is BpmnCallActivity -> out[node.id] = taskBoxSize(node.name)
                is BpmnSubProcess -> {
                    out[node.id] = taskBoxSize(node.name)
                    collect(node.flowElementNodes, out)
                }
                // Gateways/events fall through to the fixed default in sizeOf() —
                // their labels render outside the shape, see class KDoc.
                else -> {}
            }
        }
    }

    /**
     * Reproduces [BpmnLayoutBridge]'s own default sizes for element kinds this provider
     * does not measure. Kept in sync with every `?: DEFAULT_*_SIZE` fallback in
     * [BpmnLayoutBridge] — see that object's KDoc for the authoritative list of kind
     * strings passed to [sizeOf].
     */
    private fun defaultForKind(elementKind: String): Size =
        when (elementKind) {
            "BpmnGateway" -> BpmnLayoutBridge.DEFAULT_GATEWAY_SIZE
            "BpmnEvent" -> BpmnLayoutBridge.DEFAULT_EVENT_SIZE
            "BpmnDataObject" -> BpmnLayoutBridge.DEFAULT_DATA_OBJECT_SIZE
            "ChoreographyTask" -> BpmnLayoutBridge.DEFAULT_CHOREO_TASK_SIZE
            "ChoreographyGateway" -> BpmnLayoutBridge.DEFAULT_GATEWAY_SIZE
            "ChoreographyEvent" -> BpmnLayoutBridge.DEFAULT_EVENT_SIZE
            "ConversationParticipant" -> BpmnLayoutBridge.DEFAULT_CONVERSATION_PARTICIPANT_SIZE
            "CallConversation", "SubConversation", "ConversationNode" -> BpmnLayoutBridge.DEFAULT_CONVERSATION_NODE_SIZE
            else -> BpmnLayoutBridge.DEFAULT_TASK_SIZE
        }

    /**
     * Estimates the box size for a task/sub-process/call-activity label.
     *
     * Width is `label.length * TASK_CHAR_PX + BOX_H_PADDING`, floored at the existing
     * 120 px default (so short labels are unchanged) and capped at [MAX_TASK_WIDTH].
     * The cap is not just cosmetic: `name` is untrusted model input (arbitrary length
     * from a `.kuml.kts` script or an AI-generated model), and without it a pathological
     * label would directly control SVG/PNG canvas dimensions — a DoS vector analogous to
     * [dev.kuml.layout.bridge.UmlContentSizeProvider.Companion.CONNECTION_PUFFER_MAX_PX]
     * and `Sysml2LayoutBridge.STM_CONNECTION_PUFFER_MAX_PX`. Height stays fixed at the
     * default 60 px — single-line label, no word-wrap (out of scope for this fix).
     */
    private fun taskBoxSize(name: String?): Size {
        val label = name ?: ""
        val measured = label.length * TASK_CHAR_PX + BOX_H_PADDING
        val width = measured.coerceIn(BpmnLayoutBridge.DEFAULT_TASK_SIZE.width, MAX_TASK_WIDTH)
        return Size(width, BpmnLayoutBridge.DEFAULT_TASK_SIZE.height)
    }

    public companion object {
        /**
         * Estimated pixel width per character for the activity label, rendered at
         * `font-size 12` (see `renderActivityBox` in `BpmnActivitySvg`). Slightly wider
         * than [dev.kuml.layout.bridge.UmlContentSizeProvider.Companion.BODY_CHAR_PX]
         * (6.6, tuned for 11pt UML feature lines) because BPMN task labels render one
         * point larger; ~10 % slack absorbs proportional-font variance across themes
         * (Plain, kUML, Elegant, Playful).
         */
        public const val TASK_CHAR_PX: Float = 7.0f

        /** Horizontal padding added on top of the raw measured label width. */
        public const val BOX_H_PADDING: Float = 24f

        /**
         * Upper bound for a task/sub-process/call-activity box width. DoS/sanity cap —
         * see [taskBoxSize] KDoc. 480 px comfortably covers realistic long single-line
         * BPMN task labels (verified against real-world German administrative process
         * labels up to ~60 characters, e.g. "Vorstandsvorsitzender laedt ein
         * (Tagesordnung, Frist 1 Woche)" ≈ 451 px estimated) while still bounding
         * pathological input — 4× the 120 px default, same order of magnitude as the
         * UML and SysML 2 connection-puffer caps.
         */
        public const val MAX_TASK_WIDTH: Float = 480f
    }
}
