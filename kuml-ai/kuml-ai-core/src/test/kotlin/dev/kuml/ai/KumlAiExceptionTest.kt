package dev.kuml.ai

import ai.koog.prompt.llm.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class KumlAiExceptionTest :
    FunSpec({

        test("each subtype has a stable error code message prefix") {
            val privacyViolation = KumlAiException.PrivacyModeViolation(LLMProvider.OpenAI)
            privacyViolation.message shouldContain "KUML-AI-E-001"

            val missingKey = KumlAiException.MissingApiKey(LLMProvider.Anthropic)
            missingKey.message shouldContain "KUML-AI-E-002"

            val vaultUnavailable = KumlAiException.VaultUnavailable("not available")
            vaultUnavailable.message shouldContain "KUML-AI-E-003"

            val corrupted = KumlAiException.SettingsCorrupted("bad json")
            corrupted.message shouldContain "KUML-AI-E-004"

            val unknown = KumlAiException.UnknownProvider("my-provider")
            unknown.message shouldContain "KUML-AI-E-005"
        }
    })
