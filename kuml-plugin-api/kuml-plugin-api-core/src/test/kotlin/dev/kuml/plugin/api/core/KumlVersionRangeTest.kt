package dev.kuml.plugin.api.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KumlVersionRangeTest :
    FunSpec({

        test(">=3.0.27 contains 3.0.27") {
            KumlVersionRange(">=3.0.27").contains(PluginVersion(major = 3, minor = 0, patch = 27)) shouldBe true
        }

        test(">=3.0.27 contains 4.0.0") {
            KumlVersionRange(">=3.0.27").contains(PluginVersion(major = 4, minor = 0, patch = 0)) shouldBe true
        }

        test(">=3.0.27 does not contain 3.0.26") {
            KumlVersionRange(">=3.0.27").contains(PluginVersion(major = 3, minor = 0, patch = 26)) shouldBe false
        }

        test("<4.0.0 contains 3.9.99") {
            KumlVersionRange("<4.0.0").contains(PluginVersion(major = 3, minor = 9, patch = 99)) shouldBe true
        }

        test("<4.0.0 does not contain 4.0.0") {
            KumlVersionRange("<4.0.0").contains(PluginVersion(major = 4, minor = 0, patch = 0)) shouldBe false
        }

        test(">=3.0.27, <4.0.0 contains 3.1.0") {
            KumlVersionRange(">=3.0.27, <4.0.0").contains(PluginVersion(major = 3, minor = 1, patch = 0)) shouldBe true
        }

        test(">=3.0.27, <4.0.0 does not contain 4.0.0") {
            KumlVersionRange(">=3.0.27, <4.0.0").contains(PluginVersion(major = 4, minor = 0, patch = 0)) shouldBe false
        }

        test(">=3.0.27, <4.0.0 does not contain 3.0.26") {
            KumlVersionRange(">=3.0.27, <4.0.0").contains(PluginVersion(major = 3, minor = 0, patch = 26)) shouldBe false
        }

        test(">=3.0.0 contains 3.0.0 and 99.0.0") {
            val range = KumlVersionRange(">=3.0.0")
            range.contains(PluginVersion(major = 3, minor = 0, patch = 0)) shouldBe true
            range.contains(PluginVersion(major = 99, minor = 0, patch = 0)) shouldBe true
        }

        test("ANY always returns true for any version") {
            KumlVersionRange.ANY.contains(PluginVersion(major = 0, minor = 0, patch = 0)) shouldBe true
            KumlVersionRange.ANY.contains(PluginVersion(major = 1, minor = 2, patch = 3)) shouldBe true
            KumlVersionRange.ANY.contains(PluginVersion(major = 99, minor = 99, patch = 99)) shouldBe true
        }

        test("Maven-range [3.0.0,4.0.0] contains 3.0.0 and 4.0.0") {
            val range = KumlVersionRange("[3.0.0,4.0.0]")
            range.contains(PluginVersion(major = 3, minor = 0, patch = 0)) shouldBe true
            range.contains(PluginVersion(major = 4, minor = 0, patch = 0)) shouldBe true
            range.contains(PluginVersion(major = 3, minor = 5, patch = 0)) shouldBe true
        }

        test("invalid range returns false without throwing exception") {
            KumlVersionRange("INVALID_GARBAGE").contains(PluginVersion(major = 1, minor = 0, patch = 0)) shouldBe false
        }
    })
