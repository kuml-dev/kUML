package dev.kuml.runtime.activity

import dev.kuml.runtime.KumlRuntimeJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * V2.0.18 — serialization roundtrip tests for [ActivityInstance].
 */
class ActivityInstanceJsonTest :
    FunSpec({

        test("empty ActivityInstance serializes and deserializes correctly") {
            val empty = ActivityInstance()

            val json = KumlRuntimeJson.encodeToString(ActivityInstance.serializer(), empty)
            val decoded = KumlRuntimeJson.decodeFromString(ActivityInstance.serializer(), json)

            decoded shouldBe empty
            decoded.isTerminated shouldBe false
            decoded.clock shouldBe 0L
            decoded.tokenCounts shouldBe emptyMap()
        }

        test("ActivityInstance with tokens serializes and deserializes correctly") {
            val withTokens =
                ActivityInstance(
                    tokenCounts = mapOf("nodeA" to 3, "nodeB" to 1),
                    isTerminated = false,
                    clock = 7L,
                    joinTokensReceived = mapOf("joinNode" to setOf("forkA", "forkB")),
                )

            val json = KumlRuntimeJson.encodeToString(ActivityInstance.serializer(), withTokens)
            val decoded = KumlRuntimeJson.decodeFromString(ActivityInstance.serializer(), json)

            decoded shouldBe withTokens
            decoded.tokenCounts["nodeA"] shouldBe 3
            decoded.tokenCounts["nodeB"] shouldBe 1
            decoded.clock shouldBe 7L
            decoded.joinTokensReceived["joinNode"] shouldBe setOf("forkA", "forkB")
        }
    })
