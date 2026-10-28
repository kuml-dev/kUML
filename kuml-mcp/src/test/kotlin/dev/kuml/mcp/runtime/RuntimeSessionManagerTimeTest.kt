package dev.kuml.mcp.runtime

import dev.kuml.runtime.TraceEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/** Wave A2: `kuml_run_event` accepts the virtual-time events `+next` and `+<duration>`. */
class RuntimeSessionManagerTimeTest :
    FunSpec({
        val timerScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model
            import kotlin.time.Duration.Companion.seconds

            sysml2Model(name = "T") {
                val initial = stateDef(name = "Initial", isInitial = true)
                val a = stateDef(name = "A")
                val b = stateDef(name = "B")
                transition(name = "init", source = initial, target = a)
                transition(name = "ab", source = a, target = b, timer = after(duration = 5.seconds), id = "ab")
                stmDiagram(name = "T STM") {
                    include(initial)
                    include(a)
                    include(b)
                }
            }
            """.trimIndent()

        test("+4s keeps the timer armed, +1s fires it, +bogus is rejected") {
            val manager = RuntimeSessionManager()
            val started = manager.start(source = timerScript, kind = "stm", elementName = null).shouldBeInstanceOf<SessionResult.Started>()
            val id = started.sessionId

            val first = manager.event(sessionId = id, eventName = "+4s", payload = emptyMap()).shouldBeInstanceOf<SessionResult.Stepped>()
            first.activeStates shouldContain "A"
            first.stepResult shouldContain "Advanced"

            val second = manager.event(sessionId = id, eventName = "+1s", payload = emptyMap()).shouldBeInstanceOf<SessionResult.Stepped>()
            second.activeStates shouldContain "B"
            second.traceDelta
                .filterIsInstance<TraceEntry.TimerFired>()
                .single()
                .transitionId shouldBe "ab"

            val bad = manager.event(sessionId = id, eventName = "+bogus", payload = emptyMap()).shouldBeInstanceOf<SessionResult.Error>()
            bad.message shouldContain "Invalid time event"
        }

        test("+next jumps to the next timer") {
            val manager = RuntimeSessionManager()
            val started = manager.start(source = timerScript, kind = "stm", elementName = null).shouldBeInstanceOf<SessionResult.Started>()
            val r = manager.event(sessionId = started.sessionId, eventName = "+next", payload = emptyMap())
            r.shouldBeInstanceOf<SessionResult.Stepped>().activeStates shouldContain "B"
        }
    })
