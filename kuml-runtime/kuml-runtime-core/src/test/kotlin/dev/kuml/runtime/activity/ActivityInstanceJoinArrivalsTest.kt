package dev.kuml.runtime.activity

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the ADR-0015 edge-based join-arrival bookkeeping on
 * [ActivityInstance] — in particular [consumeJoinArrivals], added to fix a
 * `TokenFlowEngine` AND-join bug where [clearJoinArrivals] (correct for an
 * OR-join, which always consumes its *entire* current arrival set) was
 * reused for the PARALLEL/AND-join case too, silently dropping a surplus
 * arrival on an edge that had delivered more than once before its siblings
 * caught up — stranding the matching token forever.
 */
class ActivityInstanceJoinArrivalsTest :
    FunSpec({

        test("consumeJoinArrivals removes exactly one arrival per listed edge") {
            val instance =
                ActivityInstance()
                    .withJoinArrival(joinId = "j", edgeId = "eA")
                    .withJoinArrival(joinId = "j", edgeId = "eA")
                    .withJoinArrival(joinId = "j", edgeId = "eB")

            val updated = instance.consumeJoinArrivals(joinId = "j", edgeIds = listOf("eA", "eB"))

            // "eA" had 2 -> 1 remains; "eB" had 1 -> fully consumed, key removed.
            updated.joinEdgeTokens["j"] shouldBe mapOf("eA" to 1)
        }

        test("consumeJoinArrivals removes the join entry entirely once every edge is drained") {
            val instance =
                ActivityInstance()
                    .withJoinArrival(joinId = "j", edgeId = "eA")
                    .withJoinArrival(joinId = "j", edgeId = "eB")

            val updated = instance.consumeJoinArrivals(joinId = "j", edgeIds = listOf("eA", "eB"))

            updated.joinEdgeTokens.containsKey("j") shouldBe false
        }

        test("consumeJoinArrivals leaves an edge not present in the arrival map untouched") {
            val instance = ActivityInstance().withJoinArrival(joinId = "j", edgeId = "eA")

            // "eB" never arrived — consuming it must not throw or fabricate a negative count.
            val updated = instance.consumeJoinArrivals(joinId = "j", edgeIds = listOf("eA", "eB"))

            updated.joinEdgeTokens.containsKey("j") shouldBe false
        }

        test("consumeJoinArrivals on an unknown join id is a no-op") {
            val instance = ActivityInstance()
            instance.consumeJoinArrivals(joinId = "missing", edgeIds = listOf("eA")) shouldBe instance
        }

        test("consumeJoinArrivals does not disturb arrivals recorded for a different join") {
            val instance =
                ActivityInstance()
                    .withJoinArrival(joinId = "j1", edgeId = "eA")
                    .withJoinArrival(joinId = "j2", edgeId = "eA")

            val updated = instance.consumeJoinArrivals(joinId = "j1", edgeIds = listOf("eA"))

            updated.joinEdgeTokens.containsKey("j1") shouldBe false
            updated.joinEdgeTokens["j2"] shouldBe mapOf("eA" to 1)
        }

        test("clearJoinArrivals (OR-join contract) still wipes every arrival regardless of count") {
            val instance =
                ActivityInstance()
                    .withJoinArrival(joinId = "j", edgeId = "eA")
                    .withJoinArrival(joinId = "j", edgeId = "eA")
                    .withJoinArrival(joinId = "j", edgeId = "eB")

            instance.clearJoinArrivals("j").joinEdgeTokens.containsKey("j") shouldBe false
        }
    })
