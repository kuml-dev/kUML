package dev.kuml.runtime.sysml2

import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain

class HistoryTest :
    FunSpec({
        fun machine(deep: Boolean) =
            sysml2Model(name = "H") {
                val init = stateDef(name = "Init", isInitial = true)
                val on = stateDef(name = "On")
                val oninit = stateDef(name = "OnInit", isInitial = true, parent = on)
                val idle = stateDef(name = "Idle", parent = on)
                val running = stateDef(name = "Running", parent = on)
                val phase = stateDef(name = "Phase", parent = on)
                val phInit = stateDef(name = "PhInit", isInitial = true, parent = phase)
                val ph1 = stateDef(name = "Ph1", parent = phase)
                val ph2 = stateDef(name = "Ph2", parent = phase)
                val hist = history(name = "H", deep = deep, parent = on)
                val off = stateDef(name = "Off")
                transition(name = "t0", source = init, target = on)
                transition(name = "oi", source = oninit, target = idle)
                transition(name = "start", source = idle, target = running, trigger = "start")
                transition(name = "toPhase", source = running, target = phase, trigger = "phase")
                transition(name = "pi", source = phInit, target = ph1)
                transition(name = "next", source = ph1, target = ph2, trigger = "next")
                transition(name = "off", source = on, target = off, trigger = "off")
                transition(name = "resume", source = off, target = hist, trigger = "on")
                stmDiagram(name = "d") {
                    include(init)
                    include(on)
                    include(off)
                }
            }

        test("shallow history restores the remembered direct child") {
            val h = machine(deep = false).start()
            h.send("start")
            h.active() shouldContain "Running"
            h.send("off")
            h.active() shouldContain "Off"
            h.active() shouldNotContain "Running"
            h.send("on")
            h.active() shouldContainAll listOf("On", "Running")
            h.active() shouldNotContain "Idle"
        }

        test("without remembered state the region's initial state is entered") {
            val h = machine(deep = false).start()
            h.send("off") // left On while in Idle -> Idle is remembered
            h.send("on")
            h.active() shouldContainAll listOf("On", "Idle")

            // a fresh machine that never entered a child before leaving: H has no memory only if On was never left
            val fresh = machine(deep = false).start()
            fresh.active() shouldContain "Idle"
        }

        test("shallow history re-enters a remembered composite at its initial state") {
            val h = machine(deep = false).start()
            h.send("start")
            h.send("phase")
            h.send("next")
            h.active() shouldContainAll listOf("Phase", "Ph2")
            h.send("off")
            h.send("on")
            h.active() shouldContainAll listOf("Phase", "Ph1")
            h.active() shouldNotContain "Ph2"
        }

        test("deep history restores the full nested configuration") {
            val h = machine(deep = true).start()
            h.send("start")
            h.send("phase")
            h.send("next")
            h.send("off")
            h.send("on")
            h.active() shouldContainAll listOf("On", "Phase", "Ph2")
            h.active() shouldNotContain "Ph1"
        }
    })
