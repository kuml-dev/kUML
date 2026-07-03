package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.SequenceDiagramConfig
import dev.kuml.uml.UmlCombinedFragment
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlInteraction
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage
import dev.kuml.uml.Visibility

/**
 * Builder for a UML sequence diagram.
 *
 * A sequence diagram contains exactly one [UmlInteraction].
 *
 * Sequence numbers are assigned automatically in DSL call order — including
 * messages inside nested fragments. The flat [UmlInteraction.messages] list
 * preserves this order; combined fragments reference messages by ID.
 *
 * Do not instantiate directly — use the [dev.kuml.core.dsl.sequenceDiagram] entry-point function.
 */
@KumlDsl
class SequenceDiagramBuilder(
    private val name: String,
) : UmlInteractionScope {
    override val interactionId: String = name
    override val takenIds: MutableSet<String> = mutableSetOf(name)

    private val lifelines = mutableListOf<UmlLifeline>()
    private val messages = mutableListOf<UmlMessage>()
    private val fragments = mutableListOf<UmlCombinedFragment>()
    private val comments = mutableListOf<UmlComment>()
    private val commentLinks = mutableListOf<UmlCommentLink>()

    private var sequenceCounter = 0
    private var fragmentCounter = 0

    override fun nextSequenceNumber(): Int = ++sequenceCounter

    override fun nextFragmentIndex(): Int = ++fragmentCounter

    override fun addLifeline(lifeline: UmlLifeline) {
        lifelines += lifeline
    }

    override fun addMessage(message: UmlMessage) {
        messages += message
    }

    override fun addFragment(fragment: UmlCombinedFragment) {
        fragments += fragment
    }

    override fun addComment(comment: UmlComment) {
        comments += comment
    }

    override fun addCommentLink(link: UmlCommentLink) {
        commentLinks += link
    }

    // Interaction properties
    var interactionVisibility: Visibility = Visibility.PUBLIC
    val interactionStereotypes: MutableList<String> = mutableListOf()

    // Display options
    var showActivationBars: Boolean = true
    var showSequenceNumbers: Boolean = false
    var showReturnArrows: Boolean = true
    var numberFragmentBranches: Boolean = true

    fun build(): KumlDiagram {
        val interaction =
            UmlInteraction(
                id = interactionId,
                name = name,
                visibility = interactionVisibility,
                lifelines = lifelines.toList(),
                messages = messages.toList(),
                fragments = fragments.toList(),
                stereotypes = interactionStereotypes.toList(),
            )
        return KumlDiagram(
            name = name,
            type = DiagramType.SEQUENCE,
            elements = listOf(interaction) + comments + commentLinks,
            config =
                SequenceDiagramConfig(
                    showActivationBars = showActivationBars,
                    showSequenceNumbers = showSequenceNumbers,
                    showReturnArrows = showReturnArrows,
                    numberFragmentBranches = numberFragmentBranches,
                ),
        )
    }
}
