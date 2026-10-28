package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.StmSeverity
import dev.kuml.sysml2.constraint.Sysml2StmChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.StmEdgeAdapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TransitionTimerTest :
    FunSpec({
        val json = Json { encodeDefaults = false }

        fun codes(m: Sysml2Model) = Sysml2StmChecker.check(model = m).map { it.code }

        fun timerModel(
            timer: TransitionTimer,
            trigger: String? = null,
        ) = sysml2Model(name = "M") {
            val a = stateDef(name = "A")
            val b = stateDef(name = "B")
            transition(name = "t", source = a, target = b, trigger = trigger, timer = timer)
        }

        test("JSON: after and at round-trip, defaults are not emitted") {
            val m = timerModel(timer = TransitionTimer.After(90.seconds))
            val text = json.encodeToString(m)
            text shouldContain "PT1M30S"
            Json.decodeFromString<Sysml2Model>(text) shouldBe m

            val m2 = timerModel(timer = TransitionTimer.At(parseTimerInstant("2026-01-01T08:00:00Z")!!))
            val t2 = json.encodeToString(m2)
            t2 shouldContain "2026-01-01T08:00:00Z"
            Json.decodeFromString<Sysml2Model>(t2) shouldBe m2
        }

        test("JSON: a transition without timer neither emits timer nor guardKind") {
            val m =
                sysml2Model(name = "M") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "t", source = a, target = b, trigger = "go", guard = "x > 1")
                }
            val text = json.encodeToString(m)
            text shouldNotContain "timer"
            text shouldNotContain "guardKind"
            // a pre-A2 document (no fields) still loads
            Json.decodeFromString<Sysml2Model>(text) shouldBe m
        }

        test("durationLiteral picks the largest exact unit") {
            durationLiteral(2.days) shouldBe "2.days"
            durationLiteral(36.hours) shouldBe "36.hours"
            durationLiteral(90.seconds) shouldBe "90.seconds"
            durationLiteral(5.minutes) shouldBe "5.minutes"
            durationLiteral(1500.milliseconds) shouldBe "1500.milliseconds"
        }

        test("printer emits canonical timer literals, imports and prose guards with escaping") {
            val m =
                sysml2Model(name = "M") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "t1", source = a, target = b, timer = after(90.seconds), id = "t1")
                    transition(name = "t2", source = b, target = a, timer = at("2026-01-01T08:00:00Z"), id = "t2")
                    transition(name = "t3", source = a, target = a, trigger = "e", guard = prose("door \"open\" \$x"), id = "t3")
                }
            val dsl = Sysml2DslPrinter.print(m)
            dsl shouldContain "import kotlin.time.Duration.Companion.seconds"
            dsl shouldContain "timer = after(duration = 90.seconds)"
            dsl shouldContain "timer = at(iso = \"2026-01-01T08:00:00Z\")"
            dsl shouldContain "guard = prose(text = \"door \\\"open\\\" \\\$x\")"
        }

        test("builder rejects non-UTC at() and sub-millisecond after()") {
            shouldThrow<IllegalArgumentException> { sysml2Model(name = "M") { at("2026-01-01T08:00:00+02:00") } }
            shouldThrow<IllegalArgumentException> { sysml2Model(name = "M") { at("not a date") } }
            shouldThrow<IllegalArgumentException> { sysml2Model(name = "M") { at("2026-01-01T08:00:00.0001Z") } }
            shouldThrow<IllegalArgumentException> { sysml2Model(name = "M") { after(kotlin.time.Duration.INFINITE) } }
            shouldThrow<IllegalArgumentException> {
                sysml2Model(name = "M") {
                    val a = stateDef(name = "A")
                    transition(name = "t", source = a, target = a, guard = "x".repeat(STM_MAX_TEXT_LEN + 1))
                }
            }
        }

        test("124: timer and trigger together") {
            codes(timerModel(timer = TransitionTimer.After(5.seconds), trigger = "go")) shouldContain 124
            codes(timerModel(timer = TransitionTimer.After(5.seconds))) shouldNotContain 124
        }

        test("125: after bounds are inclusive at 1 ms and 3650 days") {
            codes(timerModel(timer = TransitionTimer.After(1.milliseconds))) shouldNotContain 125
            codes(timerModel(timer = TransitionTimer.After(3650.days))) shouldNotContain 125
            codes(timerModel(timer = TransitionTimer.After(0.milliseconds))) shouldContain 125
            codes(timerModel(timer = TransitionTimer.After(3651.days))) shouldContain 125
        }

        test("126: timer on a pseudo-state source") {
            val m =
                sysml2Model(name = "M") {
                    val c = choice(name = "C")
                    val a = stateDef(name = "A")
                    transition(name = "t", source = c, target = a, timer = after(1.seconds))
                }
            codes(m) shouldContain 126
        }

        test("127: duplicate after duration is a warning") {
            val m =
                sysml2Model(name = "M") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    val c = stateDef(name = "C")
                    transition(name = "t1", source = a, target = b, timer = after(5.seconds), id = "t1")
                    transition(name = "t2", source = a, target = c, timer = after(5.seconds), id = "t2")
                }
            val issue = Sysml2StmChecker.check(model = m).single { it.code == 127 }
            issue.severity shouldBe StmSeverity.Warning
        }

        test("128: sub-millisecond at from a JSON import") {
            val m = timerModel(timer = TransitionTimer.At(kotlin.time.Instant.parse("2026-01-01T08:00:00.0001Z")))
            codes(m) shouldContain 128
        }

        test("129: overlong texts from a JSON import") {
            val m =
                sysml2Model(name = "M") {
                    val a = stateDef(name = "A")
                    transition(name = "t", source = a, target = a, guard = "x")
                }
            val long =
                m.copy(
                    usages = m.usages.map { (it as TransitionUsage).copy(effect = "y".repeat(STM_MAX_TEXT_LEN + 1)) },
                )
            codes(long) shouldContain 129
            codes(m) shouldNotContain 129
        }

        test("edge label shows the timer and marks prose guards") {
            val m =
                sysml2Model(name = "M") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(
                        name = "t1",
                        source = a,
                        target = b,
                        timer = after(5.seconds),
                        guard = prose("door open"),
                        effect = "beep",
                        id = "t1",
                    )
                    stmDiagram(name = "d") {
                        include(a)
                        include(b)
                    }
                }
            val diagram = m.diagrams.filterIsInstance<StmDiagram>().single()
            val meta = StmEdgeAdapter(model = m, diagram = diagram).metadataFor("t1")!!
            meta.label shouldBe "after(5s) [door open] / beep"
            meta.labelParts.map { it.kind.name } shouldBe listOf("Trigger", "GuardProse", "Effect")
        }
    })
