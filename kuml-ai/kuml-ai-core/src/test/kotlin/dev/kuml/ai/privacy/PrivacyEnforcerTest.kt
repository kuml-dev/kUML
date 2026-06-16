package dev.kuml.ai.privacy

import ai.koog.prompt.llm.LLMProvider
import dev.kuml.ai.KumlAiException
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PrivacyEnforcerTest :
    FunSpec({

        test("cloud providers are blocked when privacy mode is on") {
            val enforcer = PrivacyEnforcer(privacyMode = true)
            val ex =
                shouldThrow<KumlAiException.PrivacyModeViolation> {
                    enforcer.guard(LLMProvider.OpenAI)
                }
            ex.attemptedProvider shouldBe LLMProvider.OpenAI
            ex.message shouldContain "KUML-AI-E-001"
        }

        test("ollama is always allowed regardless of mode") {
            val enforcerOn = PrivacyEnforcer(privacyMode = true)
            shouldNotThrow<KumlAiException.PrivacyModeViolation> {
                enforcerOn.guard(LLMProvider.Ollama)
            }

            val enforcerOff = PrivacyEnforcer(privacyMode = false)
            shouldNotThrow<KumlAiException.PrivacyModeViolation> {
                enforcerOff.guard(LLMProvider.Ollama)
            }
        }

        test("cloud providers are allowed when privacy mode is off") {
            val enforcer = PrivacyEnforcer(privacyMode = false)
            shouldNotThrow<KumlAiException> {
                enforcer.guard(LLMProvider.Anthropic)
            }
            enforcer.isAllowed(LLMProvider.OpenAI).shouldBeTrue()
        }

        test("isAllowed returns false for cloud when privacy mode is on") {
            val enforcer = PrivacyEnforcer(privacyMode = true)
            enforcer.isAllowed(LLMProvider.OpenAI).shouldBeFalse()
            enforcer.isAllowed(LLMProvider.Anthropic).shouldBeFalse()
            enforcer.isAllowed(LLMProvider.Ollama).shouldBeTrue()
        }
    })
