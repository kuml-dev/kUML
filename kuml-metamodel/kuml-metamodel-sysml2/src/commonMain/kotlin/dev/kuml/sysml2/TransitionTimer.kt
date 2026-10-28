@file:OptIn(kotlin.time.ExperimentalTime::class)

package dev.kuml.sysml2

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Maximum length of guard, effect and prose texts on a transition (checker code 129). */
const val STM_MAX_TEXT_LEN: Int = 512

/** Shortest permitted `after` duration (checker code 125). */
val STM_MIN_TIMER: Duration = 1.milliseconds

/** Longest permitted `after` duration (checker code 125). */
val STM_MAX_TIMER: Duration = 3650.days

/**
 * Whether a transition guard is a machine-evaluable expression or free-text prose
 * (documentation of intent only; never evaluated by the simulator on its own).
 */
@Serializable
enum class GuardKind {
    /** Parsed and evaluated by the OCL-subset expression evaluator. */
    Expression,

    /** Free text; the simulator blocks, allows or asks (see the simulator's prose-guard policy). */
    Prose,
}

/**
 * Time trigger of a [TransitionUsage]: a relative `after(duration)` timer or an
 * absolute `at(instant)` timer. A transition with a timer has no event trigger.
 */
@Serializable
sealed class TransitionTimer {
    /** Fires [duration] after the source state was entered. */
    @Serializable
    @SerialName("after")
    data class After(
        @Serializable(with = IsoDurationSerializer::class) val duration: Duration,
    ) : TransitionTimer()

    /** Fires at the absolute UTC [instant] (immediately on the next advance when already past). */
    @Serializable
    @SerialName("at")
    data class At(
        @Serializable(with = IsoInstantSerializer::class) val instant: Instant,
    ) : TransitionTimer()

    /** Short label for diagrams: `after(5s)` / `at(2026-01-01T08:00:00Z)`. */
    fun label(): String =
        when (this) {
            is After -> "after($duration)"
            is At -> "at($instant)"
        }
}

/** ISO-8601 duration (`PT5S`) as JSON string. */
object IsoDurationSerializer : KSerializer<Duration> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.kuml.sysml2.IsoDuration", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Duration,
    ) = encoder.encodeString(value.toIsoString())

    override fun deserialize(decoder: Decoder): Duration = Duration.parseIsoString(decoder.decodeString())
}

/** ISO-8601 instant with `Z` (`2026-01-01T08:00:00Z`) as JSON string. */
object IsoInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.kuml.sysml2.IsoInstant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/**
 * Parses an absolute timer instant: ISO-8601, UTC (`Z`) only, millisecond precision.
 * Returns `null` for anything else.
 */
fun parseTimerInstant(iso: String): Instant? {
    if (!iso.endsWith("Z") || iso.length > 40) return null
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    return if (instant.nanosecondsOfSecond % 1_000_000 == 0) instant else null
}

/** Kotlin source literal for [d] using the largest unit that divides it exactly (`90.seconds`). */
fun durationLiteral(d: Duration): String {
    val ms = d.inWholeMilliseconds
    return when {
        ms % 86_400_000L == 0L -> "${ms / 86_400_000L}.days"
        ms % 3_600_000L == 0L -> "${ms / 3_600_000L}.hours"
        ms % 60_000L == 0L -> "${ms / 60_000L}.minutes"
        ms % 1_000L == 0L -> "${ms / 1_000L}.seconds"
        else -> "$ms.milliseconds"
    }
}
