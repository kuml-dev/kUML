package dev.kuml.mcp.runtime

import dev.kuml.mcp.runtime.tools.RunEventTool
import dev.kuml.mcp.runtime.tools.RunPatchTool
import dev.kuml.mcp.runtime.tools.RunSnapshotTool
import dev.kuml.mcp.runtime.tools.RunStartTool
import dev.kuml.mcp.runtime.tools.RunStopTool
import dev.kuml.mcp.tools.ToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Integration tests for the five `kuml.run.*` MCP tools — exercised through
 * the tool interface rather than the session manager directly.
 */
class RuntimeToolsIntegrationTest :
    FunSpec({

        val thermostatStmScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("Thermostat") {
                val initial = stateDef("Initial", isInitial = true)
                val off = stateDef("Off")
                val idle = stateDef("Idle")
                transition("init", initial, off)
                transition("powerOn", off, idle, trigger = "powerOn")
                transition("powerOff", idle, off, trigger = "powerOff")
                stmDiagram("Thermostat STM") {
                    include(initial)
                    include(off)
                    include(idle)
                }
            }
            """.trimIndent()

        val thermostatActScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("ThermostatBoot") {
                val init = initialNode()
                val act = actionDef("DoWork", action = "work()")
                val fin = finalNode()
                controlFlow("cf1", init, act)
                controlFlow("cf2", act, fin)
                actDiagram("Boot ACT") {
                    include(init)
                    include(act)
                    include(fin)
                }
            }
            """.trimIndent()

        val manager = RuntimeSessionManager()

        // ── tool instances ─────────────────────────────────────────────────────

        val startTool = RunStartTool(manager)
        val eventTool = RunEventTool(manager)
        val snapshotTool = RunSnapshotTool(manager)
        val patchTool = RunPatchTool(manager)
        val stopTool = RunStopTool(manager)

        // ── ToolRegistry ───────────────────────────────────────────────────────

        test("ToolRegistry.tools contains all five kuml.run.* tools") {
            val names = ToolRegistry.tools.map { it.descriptor.name }
            names shouldContain "kuml.run.start"
            names shouldContain "kuml.run.event"
            names shouldContain "kuml.run.snapshot"
            names shouldContain "kuml.run.patch"
            names shouldContain "kuml.run.stop"
        }

        test("ToolRegistry.descriptors size includes original 6 plus 5 new runtime tools") {
            // 5 original + kuml.examples + 5 runtime = 11 total
            ToolRegistry.tools.size shouldBe 11
        }

        // ── STM full round-trip ────────────────────────────────────────────────

        test("STM full round-trip: start → event → snapshot → patch → stop") {
            // start
            val startArgs =
                buildJsonObject {
                    put("source", thermostatStmScript)
                    put("kind", "stm")
                }
            val startContent = startTool.call(startArgs)
            startContent.size shouldBe 1
            val startJson =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(startContent.first().text!!)
            val sessionId = startJson.jsonObject["sessionId"]!!.jsonPrimitive.content
            sessionId shouldContain "rs-"

            // event
            val eventArgs =
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("event", "powerOn")
                }
            val eventContent = eventTool.call(eventArgs)
            val eventJson =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(eventContent.first().text!!)
            val fired = eventJson.jsonObject["fired"]!!
            fired shouldNotBe null

            // snapshot
            val snapArgs = buildJsonObject { put("sessionId", sessionId) }
            val snapContent = snapshotTool.call(snapArgs)
            val snapJson =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(snapContent.first().text!!)
            snapJson.jsonObject["activeStates"] shouldNotBe null
            snapJson.jsonObject["stepCount"]!!.jsonPrimitive.content shouldBe "1"

            // patch
            val patchArgs =
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("forceState", "Off")
                }
            val patchContent = patchTool.call(patchArgs)
            val patchJson =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(patchContent.first().text!!)
            patchJson.jsonObject["ok"]!!.jsonPrimitive.content shouldBe "true"

            // stop
            val stopArgs = buildJsonObject { put("sessionId", sessionId) }
            val stopContent = stopTool.call(stopArgs)
            val stopJson =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(stopContent.first().text!!)
            stopJson.jsonObject["ok"]!!.jsonPrimitive.content shouldBe "true"
            stopJson.jsonObject["trace"] shouldNotBe null
        }

        // ── ACT full round-trip ────────────────────────────────────────────────

        test("ACT full round-trip: start → snapshot → stop") {
            val startArgs =
                buildJsonObject {
                    put("source", thermostatActScript)
                    put("kind", "act")
                }
            val startContent = startTool.call(startArgs)
            val startJson =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(startContent.first().text!!)
            val sessionId = startJson.jsonObject["sessionId"]!!.jsonPrimitive.content

            val snapArgs = buildJsonObject { put("sessionId", sessionId) }
            val snapContent = snapshotTool.call(snapArgs)
            val snapJson =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(snapContent.first().text!!)
            snapJson.jsonObject["activeStates"] shouldNotBe null

            val stopArgs = buildJsonObject { put("sessionId", sessionId) }
            val stopContent = stopTool.call(stopArgs)
            val stopJson =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(stopContent.first().text!!)
            stopJson.jsonObject["ok"]!!.jsonPrimitive.content shouldBe "true"
        }

        // ── input schema validation ────────────────────────────────────────────

        test("kuml.run.event with missing sessionId throws IllegalArgumentException") {
            val badArgs = buildJsonObject { put("event", "powerOn") }
            val ex = runCatching { eventTool.call(badArgs) }
            ex.isFailure shouldBe true
            ex.exceptionOrNull()!!.message!! shouldContain "sessionId"
        }

        test("kuml.run.start with missing source throws IllegalArgumentException") {
            val badArgs = buildJsonObject { put("kind", "stm") }
            val ex = runCatching { startTool.call(badArgs) }
            ex.isFailure shouldBe true
            ex.exceptionOrNull()!!.message!! shouldContain "source"
        }

        test("kuml.run.snapshot with missing sessionId throws IllegalArgumentException") {
            val badArgs = JsonObject(emptyMap())
            val ex = runCatching { snapshotTool.call(badArgs) }
            ex.isFailure shouldBe true
        }

        test("kuml.run.stop with missing sessionId throws IllegalArgumentException") {
            val badArgs = JsonObject(emptyMap())
            val ex = runCatching { stopTool.call(badArgs) }
            ex.isFailure shouldBe true
        }

        // ── tool descriptor names ──────────────────────────────────────────────

        test("RunStartTool descriptor name is kuml.run.start") {
            startTool.descriptor.name shouldBe "kuml.run.start"
        }

        test("RunEventTool descriptor name is kuml.run.event") {
            eventTool.descriptor.name shouldBe "kuml.run.event"
        }

        test("RunSnapshotTool descriptor name is kuml.run.snapshot") {
            snapshotTool.descriptor.name shouldBe "kuml.run.snapshot"
        }

        test("RunPatchTool descriptor name is kuml.run.patch") {
            patchTool.descriptor.name shouldBe "kuml.run.patch"
        }

        test("RunStopTool descriptor name is kuml.run.stop") {
            stopTool.descriptor.name shouldBe "kuml.run.stop"
        }
    })
