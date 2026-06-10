package dev.kuml.cli.run

import dev.kuml.cli.ExitCodes
import dev.kuml.runtime.loadEvents
import java.io.File
import java.nio.file.Path

/**
 * Batch adapter for `kuml run --adapter batch`.
 *
 * Loads a JSON events file, fires each event through the [RunSessionManager],
 * and optionally writes a trace to [traceOut].
 */
internal class BatchAdapter(
    private val manager: RunSessionManager,
    private val eventsFile: File,
    private val traceOut: Path?,
) {
    fun run(): Int {
        val events =
            try {
                loadEvents(eventsFile)
            } catch (e: Exception) {
                System.err.println("Failed to load events from '${eventsFile.path}': ${e.message}")
                return ExitCodes.IO_ERROR
            }

        var traceCount = 0

        for (event in events) {
            if (manager.isTerminated) break

            val result =
                manager.event(
                    eventName = event.name,
                    payload =
                        event.payload.mapValues { (_, v) ->
                            when {
                                v is kotlinx.serialization.json.JsonPrimitive && v.isString -> v.content
                                v is kotlinx.serialization.json.JsonPrimitive ->
                                    v.content.toBooleanStrictOrNull()
                                        ?: v.content.toLongOrNull()
                                        ?: v.content.toDoubleOrNull()
                                        ?: v.content

                                else -> v.toString()
                            }
                        },
                )

            when (result) {
                is SessionResult.Ok -> traceCount++
                is SessionResult.Terminated -> {
                    println("Session terminated after ${result.totalSteps} steps")
                    break
                }

                is SessionResult.Error -> {
                    System.err.println("Event '${event.name}' failed: ${result.message}")
                    return result.exitCode
                }
            }
        }

        traceOut?.let { out ->
            try {
                manager.writeSessionTrace(out)
                println("Wrote trace to $out")
            } catch (e: Exception) {
                System.err.println("Failed to write trace: ${e.message}")
                return ExitCodes.IO_ERROR
            }
        }

        return 0
    }
}
