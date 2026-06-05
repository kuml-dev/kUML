package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WatchLoopTest :
    FunSpec({

        test("WatchLoop invokes callback when file is modified") {
            val file = Files.createTempFile("kuml-watch-test", ".txt").toFile()
            file.writeText("initial")
            val latch = CountDownLatch(1)

            val thread =
                Thread {
                    WatchLoop.watch(file, pollIntervalMs = 100L) {
                        latch.countDown()
                    }
                }
            thread.start()

            Thread.sleep(200) // let at least one poll pass
            file.writeText("changed") // modify — updates lastModified

            val triggered = latch.await(3, TimeUnit.SECONDS)
            thread.interrupt()
            thread.join(1000)

            triggered shouldBe true
            file.delete()
        }

        test("WatchLoop does not invoke callback when file is unchanged") {
            val file = Files.createTempFile("kuml-watch-nochange", ".txt").toFile()
            file.writeText("static")
            val callCount = AtomicInteger(0)

            val thread =
                Thread {
                    WatchLoop.watch(file, pollIntervalMs = 100L) {
                        callCount.incrementAndGet()
                    }
                }
            thread.start()
            Thread.sleep(800) // 8 polls, no change
            thread.interrupt()
            thread.join(1000)

            callCount.get() shouldBe 0
            file.delete()
        }

        test("WatchLoop stops cleanly when thread is interrupted") {
            val file = Files.createTempFile("kuml-watch-stop", ".txt").toFile()
            file.writeText("content")
            val latch = CountDownLatch(1)

            val thread =
                Thread {
                    WatchLoop.watch(file, pollIntervalMs = 100L) { }
                    latch.countDown() // reaches here only after interrupt exits the loop
                }
            thread.start()
            Thread.sleep(200)
            thread.interrupt()

            val exited = latch.await(2, TimeUnit.SECONDS)
            exited shouldBe true
            file.delete()
        }
    })
