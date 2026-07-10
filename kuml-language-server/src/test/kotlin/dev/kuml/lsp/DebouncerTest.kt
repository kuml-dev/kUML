package dev.kuml.lsp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DebouncerTest :
    FunSpec({

        test("rapid submits for the same key coalesce into a single run") {
            val debouncer = Debouncer(delayMs = 100)
            try {
                val counter = AtomicInteger(0)
                val latch = CountDownLatch(1)
                repeat(5) {
                    debouncer.submit("key") {
                        counter.incrementAndGet()
                        latch.countDown()
                    }
                }
                latch.await(5, TimeUnit.SECONDS) shouldBe true
                // Give any (incorrect) extra scheduled runs a chance to fire before asserting.
                Thread.sleep(300)
                counter.get() shouldBe 1
            } finally {
                debouncer.close()
            }
        }

        test("distinct keys fire independently") {
            val debouncer = Debouncer(delayMs = 50)
            try {
                val latchA = CountDownLatch(1)
                val latchB = CountDownLatch(1)
                debouncer.submit("a") { latchA.countDown() }
                debouncer.submit("b") { latchB.countDown() }
                latchA.await(5, TimeUnit.SECONDS) shouldBe true
                latchB.await(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                debouncer.close()
            }
        }

        test("cancel prevents a pending action from running") {
            val debouncer = Debouncer(delayMs = 200)
            try {
                val ran = AtomicInteger(0)
                debouncer.submit("key") { ran.incrementAndGet() }
                debouncer.cancel("key")
                Thread.sleep(400)
                ran.get() shouldBe 0
            } finally {
                debouncer.close()
            }
        }
    })
