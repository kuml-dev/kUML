package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.uml.InteractionOperator
import dev.kuml.uml.MessageSort
import dev.kuml.uml.UmlCombinedFragment
import dev.kuml.uml.UmlInteractionOperand
import dev.kuml.uml.UmlLifeline
import dev.kuml.uml.UmlMessage
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

// ── lifeline() ────────────────────────────────────────────────────────────────

/**
 * Adds a [UmlLifeline] to the enclosing sequence diagram.
 *
 * ```kotlin
 * sequenceDiagram("Place Order") {
 *     val customer = lifeline("Customer") { isActor = true }
 *     val frontend = lifeline("Frontend")
 *     val backend  = lifeline("Backend") { represents = typeRef("OrderService") }
 * }
 * ```
 */
fun UmlInteractionScope.lifeline(
    name: String,
    id: String? = null,
    block: LifelineBuilder.() -> Unit = {},
): UmlLifeline {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.lifeline(interactionId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val builder = LifelineBuilder().apply(block)
    val ll =
        UmlLifeline(
            id = resolvedId,
            name = name,
            visibility = builder.visibility,
            represents = builder.represents,
            isActor = builder.isActor,
            stereotypes = builder.stereotypes.toList(),
        )
    addLifeline(ll)
    return ll
}

@KumlDsl
class LifelineBuilder internal constructor() {
    var visibility: Visibility = Visibility.PUBLIC
    var represents: UmlTypeRef? = null
    var isActor: Boolean = false
    val stereotypes: MutableList<String> = mutableListOf()
}

// ── message() — both scopes ───────────────────────────────────────────────────

/**
 * Sends a message from one lifeline to another at the current sequence position.
 *
 * Sequence number is assigned automatically based on DSL call order.
 *
 * @param from Source lifeline.
 * @param to Destination lifeline.
 * @param label Displayed message label (typically the operation signature).
 * @param sort Synchronous (default), asynchronous, reply, create, or delete.
 */
fun UmlInteractionScope.message(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String,
    sort: MessageSort = MessageSort.SYNC_CALL,
    id: String? = null,
): UmlMessage =
    newMessage(
        interactionId = interactionId,
        takenIds = takenIds,
        seq = nextSequenceNumber(),
        fromLifelineId = from.id,
        toLifelineId = to.id,
        label = label,
        sort = sort,
        explicitId = id,
    ).also { addMessage(it) }

fun UmlInteractionOperandScope.message(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String,
    sort: MessageSort = MessageSort.SYNC_CALL,
    id: String? = null,
): UmlMessage =
    newMessage(
        interactionId = interactionId,
        takenIds = takenIds,
        seq = nextSequenceNumber(),
        fromLifelineId = from.id,
        toLifelineId = to.id,
        label = label,
        sort = sort,
        explicitId = id,
    ).also { addMessage(it) }

private fun newMessage(
    interactionId: String,
    takenIds: MutableSet<String>,
    seq: Int,
    fromLifelineId: String,
    toLifelineId: String,
    label: String,
    sort: MessageSort,
    explicitId: String?,
): UmlMessage {
    val resolvedId =
        explicitId ?: UmlIds.disambiguate(
            candidate = UmlIds.message(interactionId, seq),
            taken = takenIds,
        )
    takenIds += resolvedId
    return UmlMessage(
        id = resolvedId,
        label = label,
        fromLifelineId = fromLifelineId,
        toLifelineId = toLifelineId,
        sort = sort,
        sequence = seq,
    )
}

// ── Convenience wrappers — sort-specific — UmlInteractionScope ────────────────

fun UmlInteractionScope.asyncMessage(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String,
    id: String? = null,
) = message(from, to, label, MessageSort.ASYNC_CALL, id)

fun UmlInteractionScope.reply(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String,
    id: String? = null,
) = message(from, to, label, MessageSort.REPLY, id)

fun UmlInteractionScope.create(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String = "«create»",
    id: String? = null,
) = message(from, to, label, MessageSort.CREATE, id)

fun UmlInteractionScope.delete(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String = "«destroy»",
    id: String? = null,
) = message(from, to, label, MessageSort.DELETE, id)

// ── Convenience wrappers — sort-specific — UmlInteractionOperandScope ─────────

fun UmlInteractionOperandScope.asyncMessage(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String,
    id: String? = null,
) = message(from, to, label, MessageSort.ASYNC_CALL, id)

fun UmlInteractionOperandScope.reply(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String,
    id: String? = null,
) = message(from, to, label, MessageSort.REPLY, id)

fun UmlInteractionOperandScope.create(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String = "«create»",
    id: String? = null,
) = message(from, to, label, MessageSort.CREATE, id)

fun UmlInteractionOperandScope.delete(
    from: UmlLifeline,
    to: UmlLifeline,
    label: String = "«destroy»",
    id: String? = null,
) = message(from, to, label, MessageSort.DELETE, id)

// ── fragment() — UmlInteractionScope ─────────────────────────────────────────

/**
 * Creates a combined fragment with the given [operator].
 *
 * For ALT and PAR, declare multiple [branch] blocks inside.
 * For OPT, LOOP, BREAK, a single branch is typical.
 *
 * ```kotlin
 * fragment(InteractionOperator.ALT) {
 *     branch(guard = "[valid]") {
 *         message(backend, db, "INSERT")
 *     }
 *     branch(guard = "[invalid]") {
 *         reply(backend, frontend, "400")
 *     }
 * }
 * ```
 */
fun UmlInteractionScope.fragment(
    operator: InteractionOperator,
    id: String? = null,
    block: FragmentBuilder.() -> Unit,
): UmlCombinedFragment {
    val fragId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.fragment(interactionId, nextFragmentIndex()),
            taken = takenIds,
        )
    takenIds += fragId
    val builder =
        FragmentBuilder(
            interactionId = interactionId,
            takenIds = takenIds,
            nextSequenceNumber = ::nextSequenceNumber,
            nextFragmentIndex = ::nextFragmentIndex,
            flatAddMessage = ::addMessage,
            flatAddFragment = ::addFragment,
        ).apply(block)
    val frag = UmlCombinedFragment(id = fragId, operator = operator, operands = builder.operands.toList())
    addFragment(frag)
    return frag
}

// ── fragment() — UmlInteractionOperandScope ───────────────────────────────────

/**
 * Creates a nested combined fragment inside an operand branch.
 *
 * The fragment is registered on the flat [UmlInteraction] lists AND its ID is
 * recorded in this operand's [UmlInteractionOperand.fragmentIds].
 * Messages inside the nested fragment's branches are NOT tracked in this operand's
 * [UmlInteractionOperand.messageIds] — they live only in the nested operands.
 */
fun UmlInteractionOperandScope.fragment(
    operator: InteractionOperator,
    id: String? = null,
    block: FragmentBuilder.() -> Unit,
): UmlCombinedFragment {
    val fragId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.fragment(interactionId, nextFragmentIndex()),
            taken = takenIds,
        )
    takenIds += fragId
    // Use flatAddMessage / flatAddFragment so that messages inside nested branches
    // go directly to the interaction-level lists, bypassing this operand's tracking.
    val flatAdd = flatAddFunctions()
    val builder =
        FragmentBuilder(
            interactionId = interactionId,
            takenIds = takenIds,
            nextSequenceNumber = ::nextSequenceNumber,
            nextFragmentIndex = ::nextFragmentIndex,
            flatAddMessage = flatAdd.first,
            flatAddFragment = flatAdd.second,
        ).apply(block)
    val frag = UmlCombinedFragment(id = fragId, operator = operator, operands = builder.operands.toList())
    // Register in this operand's fragmentIds AND on the flat interaction list
    addFragment(frag)
    return frag
}

/**
 * Returns the raw (flat-list) add functions for this scope.
 *
 * For [OperandBuilder] this bypasses local ID tracking so that messages and
 * fragments declared inside nested fragment branches are NOT double-recorded
 * in this operand's ID lists.
 */
private fun UmlInteractionOperandScope.flatAddFunctions(): Pair<(UmlMessage) -> Unit, (UmlCombinedFragment) -> Unit> =
    when (this) {
        is OperandBuilder -> Pair(rawAddMessage, rawAddFragment)
        else -> Pair(::addMessage, ::addFragment)
    }

// ── FragmentBuilder + OperandBuilder ─────────────────────────────────────────

/**
 * Builder for the operands (branches) of a [UmlCombinedFragment].
 *
 * @param flatAddMessage Adds a message directly to the flat interaction list
 *   (bypasses any intermediate operand ID tracking).
 * @param flatAddFragment Adds a fragment directly to the flat interaction list.
 */
@KumlDsl
class FragmentBuilder internal constructor(
    private val interactionId: String,
    private val takenIds: MutableSet<String>,
    private val nextSequenceNumber: () -> Int,
    private val nextFragmentIndex: () -> Int,
    internal val flatAddMessage: (UmlMessage) -> Unit,
    internal val flatAddFragment: (UmlCombinedFragment) -> Unit,
) {
    internal val operands = mutableListOf<UmlInteractionOperand>()

    /**
     * Adds one branch (operand) to the enclosing fragment.
     *
     * For ALT/PAR call multiple times. For OPT/LOOP/BREAK typically called once.
     */
    fun branch(
        guard: String? = null,
        block: OperandBuilder.() -> Unit,
    ) {
        val operandBuilder =
            OperandBuilder(
                interactionId = interactionId,
                takenIds = takenIds,
                nextSequenceNumber = nextSequenceNumber,
                nextFragmentIndex = nextFragmentIndex,
                rawAddMessage = flatAddMessage,
                rawAddFragment = flatAddFragment,
            ).apply(block)
        operands +=
            UmlInteractionOperand(
                guard = guard,
                messageIds = operandBuilder.messageIds.toList(),
                fragmentIds = operandBuilder.fragmentIds.toList(),
            )
    }
}

/**
 * Builder for a single operand (branch) inside a [UmlCombinedFragment].
 *
 * Messages and fragments declared directly in this scope are tracked in
 * [messageIds] / [fragmentIds] AND registered on the flat interaction lists
 * via [rawAddMessage] / [rawAddFragment].
 *
 * When a nested fragment is declared inside this scope, the nested fragment's
 * internal messages are forwarded via [rawAddMessage] (flat list only),
 * bypassing this operand's tracking.
 */
@KumlDsl
class OperandBuilder internal constructor(
    override val interactionId: String,
    override val takenIds: MutableSet<String>,
    private val nextSequenceNumber: () -> Int,
    private val nextFragmentIndex: () -> Int,
    /** Goes directly to the flat interaction message list. */
    internal val rawAddMessage: (UmlMessage) -> Unit,
    /** Goes directly to the flat interaction fragment list. */
    internal val rawAddFragment: (UmlCombinedFragment) -> Unit,
) : UmlInteractionOperandScope {
    internal val messageIds = mutableListOf<String>()
    internal val fragmentIds = mutableListOf<String>()

    override fun nextSequenceNumber(): Int = this.nextSequenceNumber.invoke()

    override fun nextFragmentIndex(): Int = this.nextFragmentIndex.invoke()

    /**
     * Adds a message to this operand: registers on the flat list AND records ID here.
     */
    override fun addMessage(message: UmlMessage) {
        rawAddMessage(message)
        messageIds += message.id
    }

    /**
     * Adds a nested fragment: registers on the flat list AND records its ID here.
     */
    override fun addFragment(fragment: UmlCombinedFragment) {
        rawAddFragment(fragment)
        fragmentIds += fragment.id
    }
}

// ── Convenience operator wrappers — UmlInteractionScope ──────────────────────

fun UmlInteractionScope.alt(block: FragmentBuilder.() -> Unit) = fragment(InteractionOperator.ALT, block = block)

fun UmlInteractionScope.opt(
    guard: String? = null,
    block: OperandBuilder.() -> Unit,
) = fragment(InteractionOperator.OPT) { branch(guard, block) }

fun UmlInteractionScope.loop(
    guard: String? = null,
    block: OperandBuilder.() -> Unit,
) = fragment(InteractionOperator.LOOP) { branch(guard, block) }

fun UmlInteractionScope.par(block: FragmentBuilder.() -> Unit) = fragment(InteractionOperator.PAR, block = block)

fun UmlInteractionScope.break_(
    guard: String? = null,
    block: OperandBuilder.() -> Unit,
) = fragment(InteractionOperator.BREAK) { branch(guard, block) }

// ── Convenience operator wrappers — UmlInteractionOperandScope ────────────────

fun UmlInteractionOperandScope.alt(block: FragmentBuilder.() -> Unit) = fragment(InteractionOperator.ALT, block = block)

fun UmlInteractionOperandScope.opt(
    guard: String? = null,
    block: OperandBuilder.() -> Unit,
) = fragment(InteractionOperator.OPT) { branch(guard, block) }

fun UmlInteractionOperandScope.loop(
    guard: String? = null,
    block: OperandBuilder.() -> Unit,
) = fragment(InteractionOperator.LOOP) { branch(guard, block) }

fun UmlInteractionOperandScope.par(block: FragmentBuilder.() -> Unit) = fragment(InteractionOperator.PAR, block = block)

fun UmlInteractionOperandScope.break_(
    guard: String? = null,
    block: OperandBuilder.() -> Unit,
) = fragment(InteractionOperator.BREAK) { branch(guard, block) }
