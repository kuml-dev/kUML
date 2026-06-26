package dev.kuml.profile.autosar

import dev.kuml.profile.UmlMetaclass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tests for [autosarAdaptiveProfile] — AUTOSAR Adaptive Platform profile (V3.1.35).
 *
 * Covers:
 * 1. Exactly 4 stereotypes with correct names
 * 2. Each stereotype extends the expected UmlMetaclass
 * 3. Regression guard: existing [autosarProfile] is unchanged (still 5 stereotypes)
 */
class AutosarAdaptiveProfileTest :
    FunSpec({

        val profile = autosarAdaptiveProfile

        test("autosarAdaptiveProfile declares exactly 4 stereotypes") {
            profile.stereotypes shouldHaveSize 4
        }

        test("autosarAdaptiveProfile declares stereotypes AdaptiveApplication, Machine, ServiceInstance, Manifest") {
            profile.stereotypes
                .map { it.name }
                .shouldContainExactlyInAnyOrder(
                    "AdaptiveApplication",
                    "Machine",
                    "ServiceInstance",
                    "Manifest",
                )
        }

        test("AdaptiveApplication stereotype extends UmlMetaclass.Component") {
            val st = profile.stereotypes.first { it.name == "AdaptiveApplication" }
            st.targetMetaclass shouldBe UmlMetaclass.Component
        }

        test("Machine stereotype extends UmlMetaclass.Component") {
            val st = profile.stereotypes.first { it.name == "Machine" }
            st.targetMetaclass shouldBe UmlMetaclass.Component
        }

        test("ServiceInstance stereotype extends UmlMetaclass.Component") {
            val st = profile.stereotypes.first { it.name == "ServiceInstance" }
            st.targetMetaclass shouldBe UmlMetaclass.Component
        }

        test("Manifest stereotype extends UmlMetaclass.Class") {
            val st = profile.stereotypes.first { it.name == "Manifest" }
            st.targetMetaclass shouldBe UmlMetaclass.Class
        }

        test("existing autosarProfile is unchanged — still has exactly 5 stereotypes (regression guard)") {
            autosarProfile.stereotypes shouldHaveSize 5
        }

        test("autosarAdaptiveProfile and autosarProfile are different objects") {
            autosarAdaptiveProfile shouldNotBe autosarProfile
        }

        test("autosarAdaptiveProfile namespace is dev.kuml.profiles.autosar.adaptive") {
            profile.namespace shouldBe "dev.kuml.profiles.autosar.adaptive"
        }

        test("autosarAdaptiveProfile name is AUTOSAR-Adaptive") {
            profile.name shouldBe "AUTOSAR-Adaptive"
        }
    })

private infix fun <T : Collection<*>> T.shouldHaveSize(n: Int) {
    if (size != n) throw AssertionError("Expected collection of size $n but was size $size: $this")
}
