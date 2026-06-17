package dev.kuml.plugin.api.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KumlVersionRangeTest :
    FunSpec({

        test(">=3.0.27 contains 3.0.27") {
            KumlVersionRange(">=3.0.27").contains(PluginVersion(3, 0, 27)) shouldBe true
        }

        test(">=3.0.27 contains 4.0.0") {
            KumlVersionRange(">=3.0.27").contains(PluginVersion(4, 0, 0)) shouldBe true
        }

        test(">=3.0.27 does not contain 3.0.26") {
            KumlVersionRange(">=3.0.27").contains(PluginVersion(3, 0, 26)) shouldBe false
        }

        test("<4.0.0 contains 3.9.99") {
            KumlVersionRange("<4.0.0").contains(PluginVersion(3, 9, 99)) shouldBe true
        }

        test("<4.0.0 does not contain 4.0.0") {
            KumlVersionRange("<4.0.0").contains(PluginVersion(4, 0, 0)) shouldBe false
        }

        test(">=3.0.27, <4.0.0 contains 3.1.0") {
            KumlVersionRange(">=3.0.27, <4.0.0").contains(PluginVersion(3, 1, 0)) shouldBe true
        }

        test(">=3.0.27, <4.0.0 does not contain 4.0.0") {
            KumlVersionRange(">=3.0.27, <4.0.0").contains(PluginVersion(4, 0, 0)) shouldBe false
        }

        test(">=3.0.27, <4.0.0 does not contain 3.0.26") {
            KumlVersionRange(">=3.0.27, <4.0.0").contains(PluginVersion(3, 0, 26)) shouldBe false
        }

        test(">=3.0.0 contains 3.0.0 and 99.0.0") {
            val range = KumlVersionRange(">=3.0.0")
            range.contains(PluginVersion(3, 0, 0)) shouldBe true
            range.contains(PluginVersion(99, 0, 0)) shouldBe true
        }

        test("ANY always returns true for any version") {
            KumlVersionRange.ANY.contains(PluginVersion(0, 0, 0)) shouldBe true
            KumlVersionRange.ANY.contains(PluginVersion(1, 2, 3)) shouldBe true
            KumlVersionRange.ANY.contains(PluginVersion(99, 99, 99)) shouldBe true
        }

        test("Maven-range [3.0.0,4.0.0] contains 3.0.0 and 4.0.0") {
            val range = KumlVersionRange("[3.0.0,4.0.0]")
            range.contains(PluginVersion(3, 0, 0)) shouldBe true
            range.contains(PluginVersion(4, 0, 0)) shouldBe true
            range.contains(PluginVersion(3, 5, 0)) shouldBe true
        }

        test("invalid range returns false without throwing exception") {
            KumlVersionRange("INVALID_GARBAGE").contains(PluginVersion(1, 0, 0)) shouldBe false
        }
    })
