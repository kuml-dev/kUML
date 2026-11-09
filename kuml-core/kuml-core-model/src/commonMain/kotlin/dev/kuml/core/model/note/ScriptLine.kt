package dev.kuml.core.model.note

/**
 * Line of the `.kts` script frame that is currently executing, or `null` when no script frame is on the stack
 * (a model built in plain Kotlin, JSON decoding, the JS and wasm targets).
 *
 * Note builders call this once per `comment(...)` / `note(...)`, so a finding about the note can name the line the
 * author wrote it on. The result is bounded by the 200-note cap per diagram. Only the line number is read from the
 * stack trace, never a path or any other text, so nothing of the host leaks into a model or a message.
 */
fun callerScriptLine(): Int? =
    try {
        SCRIPT_FRAME
            .find(Throwable().stackTraceToString())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable,
    ) {
        null
    }

private val SCRIPT_FRAME: Regex = Regex("""\.kts:(\d{1,7})\)""")
