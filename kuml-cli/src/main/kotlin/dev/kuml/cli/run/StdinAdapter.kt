package dev.kuml.cli.run

import dev.kuml.runtime.KumlRuntimeJson
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.PrintStream

/**
 * Interactive stdin adapter for `kuml run --adapter stdin`.
 *
 * Reads lines from [input], parses them as `<eventName> [jsonPayload]`,
 * dispatches to the [RunSessionManager], and writes results to [output].
 *
 * ## Special commands
 *  - `quit` / `exit` — terminate the session and return 0
 *  - `snapshot` — print current state as JSON
 *  - `status` — print active state IDs
 *
 * ## Event format
 *  - `<eventName>` — event with no payload
 *  - `<eventName> {"key":"value"}` — event with JSON payload
 */
internal class StdinAdapter(
    private val manager: RunSessionManager,
    private val input: InputStream = System.`in`,
    private val output: PrintStream = System.out,
) {
    fun run(): Int {
        val reader = input.bufferedReader()
        output.println("kuml run (stdin adapter) — type 'quit' to exit, 'status' for active states")

        while (true) {
            val line =
                try {
                    reader.readLine()?.trim() ?: break
                } catch (_: Exception) {
                    break
                }
            if (line.isEmpty()) continue

            when (line) {
                "quit", "exit" -> {
                    manager.stop()
                    return 0
                }

                "snapshot" -> {
                    val result = manager.snapshot()
                    when (result) {
                        is SessionResult.Ok ->
                            output.println(
                                """{"activeStates":${result.activeStates.asJsonArray()},"stepInfo":"${result.message ?: ""}"}""",
                            )

                        is SessionResult.Terminated ->
                            output.println("""{"terminated":true,"totalSteps":${result.totalSteps}}""")

                        is SessionResult.Error ->
                            output.println("""{"error":"${result.message.replace("\"", "\\\"")}"}""")
                    }
                }

                "status" -> {
                    val result = manager.snapshot()
                    when (result) {
                        is SessionResult.Ok ->
                            output.println("Active states: ${result.activeStates.joinToString(", ")}")

                        is SessionResult.Terminated ->
                            output.println("Session terminated (steps=${result.totalSteps})")

                        is SessionResult.Error ->
                            output.println("Error: ${result.message}")
                    }
                }

                else -> {
                    val (eventName, payload) = parseLine(line)
                    val result = manager.event(eventName, payload)
                    when (result) {
                        is SessionResult.Ok -> {
                            output.println("→ ${result.message ?: "ok"}")
                            output.println("  Active: ${result.activeStates.joinToString(", ")}")
                        }

                        is SessionResult.Terminated -> {
                            output.println("Session terminated after ${result.totalSteps} steps.")
                            return 0
                        }

                        is SessionResult.Error -> {
                            output.println("Error: ${result.message}")
                            if (result.exitCode != 0) return result.exitCode
                        }
                    }
                }
            }

            if (manager.isTerminated) {
                output.println("State machine terminated.")
                return 0
            }
        }

        manager.stop()
        return 0
    }

    private fun parseLine(line: String): Pair<String, Map<String, Any>> {
        val ws = line.indexOf(' ')
        if (ws < 0) return line to emptyMap()
        val name = line.substring(0, ws).trim()
        val rest = line.substring(ws).trim()
        val payload: Map<String, Any> =
            try {
                val json = KumlRuntimeJson.parseToJsonElement(rest).jsonObject
                json.mapValues { (_, v) ->
                    when {
                        v is kotlinx.serialization.json.JsonPrimitive && v.isString -> v.content
                        v is kotlinx.serialization.json.JsonPrimitive ->
                            v.content.toBooleanStrictOrNull()
                                ?: v.content.toLongOrNull()
                                ?: v.content.toDoubleOrNull()
                                ?: v.content

                        else -> v.toString()
                    }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        return name to payload
    }

    private fun List<String>.asJsonArray(): String = joinToString(",", "[", "]") { "\"$it\"" }
}
