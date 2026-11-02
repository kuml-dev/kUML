package dev.kuml.desktop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.util.Properties

class AppVersionTest :
    FunSpec({
        test("current is the build's project version, expanded from the resource (not the 'dev' fallback)") {
            AppVersion.current shouldNotBe AppVersion.UNKNOWN
            AppVersion.current shouldMatch Regex("""\d+\.\d+\.\d+([-+].+)?""")
        }

        test("parse accepts an expanded version") {
            AppVersion.parse(Properties().apply { setProperty("version", " 1.2.3 ") }) shouldBe "1.2.3"
        }

        test("parse rejects an unexpanded placeholder, a blank value and a missing key") {
            AppVersion.parse(Properties().apply { setProperty("version", "\${kumlVersion}") }) shouldBe null
            AppVersion.parse(Properties().apply { setProperty("version", "  ") }) shouldBe null
            AppVersion.parse(Properties()) shouldBe null
        }

        test("parseBuildDate accepts an ISO date") {
            AppVersion.parseBuildDate(Properties().apply { setProperty("buildDate", " 2026-10-29 ") }) shouldBe "2026-10-29"
        }

        test("parseBuildDate rejects placeholder, blank, missing and non-ISO values") {
            listOf("\${kumlBuildDate}", " ", "29.10.2026", "2026-10-29T10:00", "2026-10-29\n<x>").forEach {
                AppVersion.parseBuildDate(Properties().apply { setProperty("buildDate", it) }) shouldBe null
            }
            AppVersion.parseBuildDate(Properties()) shouldBe null
        }

        test("buildDate is null or an ISO date (git is not required)") {
            AppVersion.buildDate?.shouldMatch(Regex("""\d{4}-\d{2}-\d{2}"""))
        }
    })
