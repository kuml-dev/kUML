package dev.kuml.io.svg

import dev.kuml.io.svg.sysml2.SYSML2_SEQ_HEAD_HEIGHT
import dev.kuml.io.svg.sysml2.SYSML2_SEQ_MESSAGE_ROW_HEIGHT
import dev.kuml.io.svg.sysml2.SeqFragmentTree
import dev.kuml.io.svg.sysml2.SeqRowOffsets
import dev.kuml.io.svg.sysml2.nextLifelineAxisX
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.bridge.note.SeqNoteScene
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.DurationConstraintUsage
import dev.kuml.sysml2.ExecutionSpecificationUsage
import dev.kuml.sysml2.InteractionUseUsage
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TimeObservationUsage
import dev.kuml.uml.UmlInteraction

/**
 * The scenes the sequence note placement works on (Nacharbeit 39). Both are read from what the real draw functions
 * produce (see [SeqSceneCapture]) so notes avoid exactly what is drawn; the layouts are the unshifted ones, a uniform
 * translation the renderer applies later does not change the relations between notes and the drawing.
 */
internal object SeqNoteScenes {
    /** Scene of a SysML 2 sequence diagram; [layout] is the normalized lifeline layout (no notes yet). */
    fun sysml2(
        model: Sysml2Model,
        diagram: SeqDiagram,
        layout: LayoutResult,
    ): SeqNoteScene {
        val ids = diagram.elementIds.toSet()
        val lifelines: List<LifelineDefinition> =
            diagram.elementIds.mapNotNull { id -> model.definitions.firstOrNull { it.id == id } as? LifelineDefinition }
        val layouts: Map<NodeId, NodeLayout> =
            lifelines.mapNotNull { l -> layout.nodes[NodeId(l.id)]?.let { NodeId(l.id) to it } }.toMap()
        val messages = model.usages.filterIsInstance<MessageUsage>()
        val fragments = model.usages.filterIsInstance<CombinedFragmentUsage>()
        val execSpecs = model.usages.filterIsInstance<ExecutionSpecificationUsage>().filter { it.lifelineId in ids }
        val refs = model.usages.filterIsInstance<InteractionUseUsage>().filter { u -> u.lifelineIds.any { it in ids } }
        val durations = model.usages.filterIsInstance<DurationConstraintUsage>().filter { it.lifelineId in ids }
        val observations = model.usages.filterIsInstance<TimeObservationUsage>().filter { it.lifelineId in ids }
        val tree = SeqFragmentTree(fragments)
        val rows = SeqRowOffsets.of(fragments = fragments, refs = refs, messages = messages)
        val createOffset: Map<String, Float> =
            messages
                .filter { it.kind == MessageKind.Create && it.targetLifelineId in ids }
                .associate {
                    it.targetLifelineId to
                        (it.seqNo + 1) * SYSML2_SEQ_MESSAGE_ROW_HEIGHT + rows.offsetAt(it.seqNo)
                }
        val rowReach = HashMap<Int, Float>()
        for (m in messages) {
            if (m.sourceLifelineId == m.targetLifelineId && m.kind != MessageKind.Found && m.kind != MessageKind.Lost) {
                rowReach[m.seqNo] = maxOf(rowReach[m.seqNo] ?: 0f, dev.kuml.io.svg.sysml2.SYSML2_SELF_CALL_HEIGHT)
            }
        }
        for (u in refs) rowReach[u.seqNo] = maxOf(rowReach[u.seqNo] ?: 0f, dev.kuml.io.svg.sysml2.SYSML2_REF_HEIGHT / 2f)

        val b = SeqSceneCapture.Builder()
        val theme = PlainTheme()
        val visibleLayouts = lifelines.mapNotNull { layouts[NodeId(it.id)] }

        val heads =
            SeqSceneCapture.capture { sb ->
                val showKeyword =
                    lifelines.none {
                        dev.kuml.layout.bridge
                            .seqHeadLines(name = it.name)
                            .size > 1
                    }
                for (l in lifelines) {
                    val nl = layouts[NodeId(l.id)] ?: continue
                    dev.kuml.io.svg.sysml2.renderLifelineHead(
                        element = l,
                        layout = nl,
                        theme = theme,
                        builder = sb,
                        createOffsetY = createOffset[l.id] ?: 0f,
                        showKeyword = showKeyword,
                    )
                }
            }
        val (headRects, stems) = SeqSceneCapture.headsAndStems(heads)
        b.heads += headRects
        b.stems += stems

        for (fragment in fragments) {
            val covered =
                dev.kuml.io.svg.sysml2
                    .coveredLifelines(fragment = fragment, usages = model.usages, tree = tree)
            b.fragment(
                SeqSceneCapture.capture { sb ->
                    dev.kuml.io.svg.sysml2.renderCombinedFragment(
                        fragment = fragment,
                        visibleLifelineLayouts = visibleLayouts,
                        builder = sb,
                        rows = rows,
                        tree = tree,
                        coveredLayouts = covered.mapNotNull { layouts[NodeId(it)] },
                        rowReach = rowReach,
                    )
                },
            )
        }
        b.solid(
            SeqSceneCapture.capture { sb ->
                for (es in execSpecs) {
                    val nl = layouts[NodeId(es.lifelineId)] ?: continue
                    dev.kuml.io.svg.sysml2
                        .renderExecutionSpec(
                            execSpec = es,
                            lifelineLayout = nl,
                            builder = sb,
                            rows = rows,
                            nextLifelineCx = nextLifelineAxisX(layout = nl, all = layouts.values),
                        )
                }
                for (use in refs) {
                    dev.kuml.io.svg.sysml2
                        .renderInteractionUse(use = use, nodeLayouts = layouts, rows = rows, builder = sb)
                }
                for (dc in durations) {
                    val nl = layouts[NodeId(dc.lifelineId)] ?: continue
                    val hasBar =
                        execSpecs.any {
                            it.lifelineId == dc.lifelineId &&
                                it.startSeqNo <= minOf(dc.fromSeqNo, dc.toSeqNo) &&
                                it.endSeqNo >= maxOf(dc.fromSeqNo, dc.toSeqNo)
                        }
                    dev.kuml.io.svg.sysml2.renderDurationConstraint(
                        constraint = dc,
                        lifelineLayout = nl,
                        hasActivationBar = hasBar,
                        rows = rows,
                        builder = sb,
                    )
                }
                for (o in observations) {
                    val nl = layouts[NodeId(o.lifelineId)] ?: continue
                    // the side of the label depends on the final padding; keep both sides free
                    for (left in listOf(true, false)) {
                        dev.kuml.io.svg.sysml2.renderTimeObservation(
                            observation = o,
                            lifelineLayout = nl,
                            rows = rows,
                            builder = sb,
                            labelLeft = left,
                            belowLine =
                                dev.kuml.io.svg.sysml2
                                    .timeObservationSharesRow(observation = o, messages = messages),
                        )
                    }
                }
            },
        )
        b.messagesDrawn(
            SeqSceneCapture.capture { sb ->
                dev.kuml.io.svg.sysml2.renderSysml2SeqMessages(
                    messages = messages,
                    visibleLifelineIds = ids,
                    nodeLayouts = layouts,
                    builder = sb,
                    rows = rows,
                    execSpecs = execSpecs,
                )
            },
        )

        // anchors of notes on messages: the drawn line between the stems at the row's height
        for (m in messages) {
            val a = stems[m.sourceLifelineId] ?: continue
            val c = stems[m.targetLifelineId] ?: continue
            val head = layouts[NodeId(m.sourceLifelineId)] ?: continue
            val y = head.bounds.origin.y + SYSML2_SEQ_HEAD_HEIGHT + (m.seqNo + 1) * SYSML2_SEQ_MESSAGE_ROW_HEIGHT + rows.offsetAt(m.seqNo)
            val x2 = if (a.x == c.x) a.x + 1f else c.x
            b.messages[m.id] = listOf(Point(x = a.x, y = y), Point(x = x2, y = y))
        }
        return b.build(rowPitch = SYSML2_SEQ_MESSAGE_ROW_HEIGHT)
    }

    /** Scene of a UML sequence diagram; [layout] holds the lifeline nodes (no notes). */
    fun uml(
        interaction: UmlInteraction,
        layout: LayoutResult,
        labelBackdropFill: String = "white",
    ): SeqNoteScene {
        val ids = interaction.lifelines.map { it.id }.toSet()
        val layouts: Map<NodeId, NodeLayout> =
            interaction.lifelines.mapNotNull { l -> layout.nodes[NodeId(l.id)]?.let { NodeId(l.id) to it } }.toMap()
        val visibleLayouts = interaction.lifelines.mapNotNull { layouts[NodeId(it.id)] }
        val msgById = interaction.messages.associateBy { it.id }
        val operandFirstSeqs =
            dev.kuml.io.svg.uml
                .umlOperandFirstSeqs(fragments = interaction.fragments, msgById = msgById)
        val b = SeqSceneCapture.Builder()

        val heads =
            SeqSceneCapture.capture { sb ->
                val showKeyword =
                    interaction.lifelines.none {
                        dev.kuml.layout.bridge
                            .seqHeadLines(name = it.name)
                            .size > 1
                    }
                for (l in interaction.lifelines) {
                    val nl = layouts[NodeId(l.id)] ?: continue
                    dev.kuml.io.svg.uml
                        .renderUmlLifelineHead(
                            element = l,
                            layout = nl,
                            theme = PlainTheme(),
                            builder = sb,
                            showKeyword = showKeyword,
                        )
                }
            }
        val (headRects, stems) = SeqSceneCapture.headsAndStems(heads)
        b.heads += headRects
        b.stems += stems

        // the fragments of one interaction share the guard pass, so they are captured together: frames are the rects
        // of the fragments' own groups, everything else a solid block
        val guardLabels = ArrayList<dev.kuml.io.svg.uml.SeqGuardLabel>()
        val all =
            SeqSceneCapture.capture { sb ->
                val r =
                    dev.kuml.io.svg.uml.renderUmlCombinedFragments(
                        fragments = interaction.fragments,
                        interaction = interaction,
                        visibleLifelineLayouts = visibleLayouts,
                        builder = sb,
                        labelBackdropFill = labelBackdropFill,
                    )
                guardLabels += r.guardLabels
            }
        val fragmentIds = interaction.fragments.map { it.id }.toSet()
        for (fragment in interaction.fragments) b.fragment(all.filter { it.groupId == fragment.id })
        b.solid(all.filter { it.groupId !in fragmentIds })
        b.solid(
            SeqSceneCapture.capture { sb ->
                dev.kuml.io.svg.uml
                    .renderUmlGuardLabels(guards = guardLabels, builder = sb)
            },
        )
        b.messagesDrawn(
            SeqSceneCapture.capture { sb ->
                dev.kuml.io.svg.uml.renderUmlSeqMessages(
                    messages = interaction.messages,
                    visibleLifelineIds = ids,
                    nodeLayouts = layouts,
                    builder = sb,
                    operandFirstSeqs = operandFirstSeqs,
                    labelBackdropFill = labelBackdropFill,
                )
            },
        )
        for (m in interaction.messages) {
            val a = stems[m.fromLifelineId] ?: continue
            val c = stems[m.toLifelineId] ?: continue
            val head = layouts[NodeId(m.fromLifelineId)] ?: continue
            val y =
                head.bounds.origin.y + dev.kuml.io.svg.uml.SEQ_HEAD_HEIGHT +
                    m.sequence * dev.kuml.io.svg.uml.SEQ_ROW_HEIGHT +
                    dev.kuml.io.svg.uml
                        .umlSeqRowOffset(sequence = m.sequence, operandFirstSeqs = operandFirstSeqs)
            val x2 = if (a.x == c.x) a.x + 1f else c.x
            b.messages[m.id] = listOf(Point(x = a.x, y = y), Point(x = x2, y = y))
        }
        return b.build(rowPitch = dev.kuml.io.svg.uml.SEQ_ROW_HEIGHT)
    }
}
