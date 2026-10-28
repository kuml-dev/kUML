package dev.kuml.runtime.sysml2

import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class ForkJoinTest :
    FunSpec({
        fun model() =
            sysml2Model(name = "FJ") {
                val init = stateDef(name = "Init", isInitial = true)
                val start = stateDef(name = "Start")
                val p = stateDef(name = "P")
                val ra = region(owner = p, name = "A")
                val rb = region(owner = p, name = "B")
                val rc = region(owner = p, name = "C")
                val ia = stateDef(name = "IA", isInitial = true, parent = p, region = ra)
                val aWork = stateDef(name = "AWork", parent = p, region = ra)
                val aDone = stateDef(name = "ADone", parent = p, region = ra)
                val ib = stateDef(name = "IB", isInitial = true, parent = p, region = rb)
                val bWork = stateDef(name = "BWork", parent = p, region = rb)
                val bDone = stateDef(name = "BDone", parent = p, region = rb)
                // region C has an initial state but is not a fork target: it is entered implicitly
                val ic = stateDef(name = "IC", isInitial = true, parent = p, region = rc)
                val cIdle = stateDef(name = "CIdle", parent = p, region = rc)
                val finished = stateDef(name = "Finished")
                val fork = fork(name = "F")
                val join = join(name = "J")
                transition(name = "t0", source = init, target = start)
                transition(name = "go", source = start, target = fork, trigger = "go")
                transition(name = "f1", source = fork, target = aWork)
                transition(name = "f2", source = fork, target = bWork)
                transition(name = "ia", source = ia, target = aDone)
                transition(name = "ib", source = ib, target = bDone)
                transition(name = "ic", source = ic, target = cIdle)
                transition(name = "advA", source = aWork, target = aDone, trigger = "advA")
                transition(name = "advB", source = bWork, target = bDone, trigger = "advB")
                transition(name = "jA", source = aDone, target = join, trigger = "done", id = "jA")
                transition(name = "jB", source = bDone, target = join, trigger = "done", id = "jB")
                transition(name = "jOut", source = join, target = finished, effect = "wrapUp", id = "jOut")
                stmDiagram(name = "d") {
                    include(init)
                    include(start)
                    include(p)
                    include(fork)
                    include(join)
                    include(finished)
                }
            }

        test("fork enters the target regions and the implicit initial of the others") {
            val h = model().start()
            h.send("go")
            h.active() shouldContainAll listOf("P", "AWork", "BWork", "CIdle")
            h.active() shouldNotContain "Start"
        }

        test("join waits until every source region is in its source state, then fires once") {
            val h = model().start()
            h.send("go")
            h.send("advA")
            h.send("done")
            val waiting =
                h.instance.trace
                    .filterIsInstance<TraceEntry.JoinWaiting>()
                    .single()
            waiting.joinId shouldBe "J"
            waiting.missing shouldBe listOf("BDone")
            h.active() shouldContain "ADone"
            h.active() shouldNotContain "Finished"

            h.send("advB")
            h.send("done")
            h.active() shouldContain "Finished"
            h.active() shouldNotContain "P"
            h.active() shouldNotContain "ADone"
            h.instance.trace
                .filterIsInstance<TraceEntry.TransitionFired>()
                .count { it.transitionId == "jOut" } shouldBe 1
        }

        test("a timer leg into a join is not ready before its timer fired") {
            val m =
                sysml2Model(name = "TimerJoin") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val p = stateDef(name = "P")
                    val ra = region(owner = p, name = "A")
                    val rb = region(owner = p, name = "B")
                    val ia = stateDef(name = "IA", isInitial = true, parent = p, region = ra)
                    val aDone = stateDef(name = "ADone", parent = p, region = ra)
                    val ib = stateDef(name = "IB", isInitial = true, parent = p, region = rb)
                    val bDone = stateDef(name = "BDone", parent = p, region = rb)
                    val finished = stateDef(name = "Finished")
                    val join = join(name = "J")
                    transition(name = "t0", source = init, target = p)
                    transition(name = "ia", source = ia, target = aDone)
                    transition(name = "ib", source = ib, target = bDone)
                    transition(name = "jA", source = aDone, target = join, timer = after(5.seconds), id = "jA")
                    transition(name = "jB", source = bDone, target = join, trigger = "done", id = "jB")
                    transition(name = "jOut", source = join, target = finished, id = "jOut")
                    stmDiagram(name = "d") {
                        include(init)
                        include(p)
                        include(join)
                        include(finished)
                    }
                }
            val h = m.start()
            h.send("done")
            h.active() shouldContainAll listOf("ADone", "BDone")
            h.active() shouldNotContain "Finished"
        }

        fun timerJoinModel() =
            sysml2Model(name = "TimerJoinLate") {
                val init = stateDef(name = "Init", isInitial = true)
                val p = stateDef(name = "P")
                val ra = region(owner = p, name = "A")
                val rb = region(owner = p, name = "B")
                val ia = stateDef(name = "IA", isInitial = true, parent = p, region = ra)
                val aDone = stateDef(name = "ADone", parent = p, region = ra)
                val ib = stateDef(name = "IB", isInitial = true, parent = p, region = rb)
                val bWait = stateDef(name = "BWait", parent = p, region = rb)
                val bDone = stateDef(name = "BDone", parent = p, region = rb)
                val finished = stateDef(name = "Finished")
                val join = join(name = "J")
                transition(name = "t0", source = init, target = p)
                transition(name = "ia", source = ia, target = aDone)
                transition(name = "ib", source = ib, target = bWait)
                transition(name = "late", source = bWait, target = bDone, trigger = "late")
                transition(name = "jA", source = aDone, target = join, timer = after(5.seconds), id = "jA")
                transition(name = "jB", source = bDone, target = join, trigger = "done", id = "jB")
                transition(name = "jOut", source = join, target = finished, id = "jOut")
                stmDiagram(name = "d") {
                    include(init)
                    include(p)
                    include(join)
                    include(finished)
                }
            }

        test("a timer leg that fired early joins with an event-triggered leg") {
            val h = timerJoinModel().start()
            h.runtime.advance(instance = h.instance, by = 5.seconds)
            h.active() shouldContainAll listOf("ADone", "BWait")
            h.send("late")
            h.active() shouldNotContain "Finished"
            h.send("done")
            h.active() shouldContain "Finished"
            h.active() shouldNotContain "P"
        }
    })
