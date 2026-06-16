package dev.kuml.ai.privacy

import ai.koog.prompt.llm.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class PrivacyModeTest :
    FunSpec({

        test("LOCAL_PROVIDERS contains exactly Ollama") {
            PrivacyMode.LOCAL_PROVIDERS shouldContainExactly setOf(LLMProvider.Ollama)
        }

        test("isLocal returns true for Ollama and false for cloud providers") {
            PrivacyMode.isLocal(LLMProvider.Ollama) shouldBe true
            PrivacyMode.isLocal(LLMProvider.OpenAI) shouldBe false
            PrivacyMode.isLocal(LLMProvider.Anthropic) shouldBe false
            PrivacyMode.isLocal(LLMProvider.Google) shouldBe false
        }
    })
