package dev.kuml.llm.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LlmMockBackendTest :
    StringSpec({

        "LlmMockBackend returns configured response" {
            val expectedResponse = "diagram(name = \"Test\") { }"
            val backend = LlmMockBackend(response = expectedResponse)
            val result = backend.complete(messages = listOf(LlmMessage(role = "user", content = "hello")))
            result.content shouldBe expectedResponse
            result.model shouldBe "mock"
            result.inputTokens shouldBe 0
            result.outputTokens shouldBe 0
        }

        "LlmMockBackend increments callCount" {
            val backend = LlmMockBackend()
            backend.callCount shouldBe 0
            backend.complete(messages = listOf(LlmMessage(role = "user", content = "first")))
            backend.callCount shouldBe 1
            backend.complete(messages = listOf(LlmMessage(role = "user", content = "second")))
            backend.callCount shouldBe 2
            backend.complete(messages = listOf(LlmMessage(role = "user", content = "third")))
            backend.callCount shouldBe 3
        }
    })
