package dev.kuml.cli.update

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SemVerTest :
    StringSpec({

        "parses a plain three-number version" {
            SemVer.parseOrNull("0.4.0") shouldBe SemVer(0, 4, 0)
        }

        "strips a leading 'v' so GitHub tags work verbatim" {
            SemVer.parseOrNull("v0.4.0") shouldBe SemVer(0, 4, 0)
            SemVer.parseOrNull("V1.2.3") shouldBe SemVer(1, 2, 3)
        }

        "parses pre-release and build metadata" {
            SemVer.parseOrNull("1.2.3-rc.1+sha.deadbee") shouldBe
                SemVer(1, 2, 3, preRelease = "rc.1", buildMetadata = "sha.deadbee")
        }

        "rejects garbage" {
            SemVer.parseOrNull("not-a-version") shouldBe null
            SemVer.parseOrNull("1.2") shouldBe null
            SemVer.parseOrNull("1.2.3.4") shouldBe null
            SemVer.parseOrNull("") shouldBe null
        }

        "core version dominates comparison" {
            (SemVer(0, 4, 0) < SemVer(0, 5, 0)) shouldBe true
            (SemVer(1, 0, 0) > SemVer(0, 99, 99)) shouldBe true
            (SemVer(0, 4, 0) == SemVer(0, 4, 0)) shouldBe true
        }

        "stable beats pre-release at the same core" {
            // 1.0.0-rc.1 < 1.0.0 — per SemVer spec point 11.
            val rc = SemVer.parseOrNull("1.0.0-rc.1")!!
            val ga = SemVer.parseOrNull("1.0.0")!!
            (rc < ga) shouldBe true
        }

        "pre-release identifiers compare numerically when both are numbers" {
            val a = SemVer.parseOrNull("1.0.0-rc.2")!!
            val b = SemVer.parseOrNull("1.0.0-rc.10")!!
            // rc.2 < rc.10 — numeric, not lexicographic.
            (a < b) shouldBe true
        }

        "build metadata is ignored in ordering" {
            val a = SemVer.parseOrNull("1.0.0+sha.aaaa")!!
            val b = SemVer.parseOrNull("1.0.0+sha.bbbb")!!
            a.compareTo(b) shouldBe 0
        }

        "round-trips through toString" {
            val s = "1.2.3-rc.1+build.42"
            SemVer.parseOrNull(s)!!.toString() shouldBe s
        }

        "isPreRelease reports the suffix" {
            SemVer.parseOrNull("1.0.0")!!.isPreRelease shouldBe false
            SemVer.parseOrNull("1.0.0-rc.1")!!.isPreRelease shouldBe true
        }
    })
