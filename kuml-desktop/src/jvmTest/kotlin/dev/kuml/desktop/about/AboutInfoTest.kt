package dev.kuml.desktop.about

import dev.kuml.desktop.i18n.Strings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class AboutInfoTest :
    FunSpec({
        test("rows are version, build date, java in order (DE)") {
            val rows = AboutInfo.rows(strings = Strings.DE, version = "0.55.3", buildDate = "2026-10-29", javaVersion = "21 (X)")
            rows.map { it.label } shouldBe listOf("Version", "Build-Datum", "Java")
            rows.map { it.value } shouldBe listOf("0.55.3", "2026-10-29", "21 (X)")
        }

        test("rows labels in EN") {
            AboutInfo.rows(strings = Strings.EN, version = "1", buildDate = "2026-01-01", javaVersion = "21").map { it.label } shouldBe
                listOf("Version", "Build date", "Java")
        }

        test("missing or blank values show the unknown text") {
            val de = AboutInfo.rows(strings = Strings.DE, version = "1", buildDate = null, javaVersion = "  ")
            de[1].value shouldBe "unbekannt"
            de[2].value shouldBe "unbekannt"
            AboutInfo.rows(strings = Strings.EN, version = "1", buildDate = null, javaVersion = null)[1].value shouldBe "unknown"
        }

        test("clipboard text is exactly the displayed rows") {
            val rows = AboutInfo.rows(strings = Strings.DE, version = "0.55.3", buildDate = "2026-10-29", javaVersion = "21 (X)")
            AboutInfo.clipboardText(rows) shouldBe "Version: 0.55.3\nBuild-Datum: 2026-10-29\nJava: 21 (X)"
        }

        test("javaDescription with and without vendor") {
            AboutInfo.javaDescription(version = "21.0.5", vendor = "Eclipse Adoptium") shouldBe "21.0.5 (Eclipse Adoptium)"
            AboutInfo.javaDescription(version = "21.0.5", vendor = null) shouldBe "21.0.5"
            AboutInfo.javaDescription(version = "21.0.5", vendor = " ") shouldBe "21.0.5"
            AboutInfo.javaDescription(version = null, vendor = "X") shouldBe null
            AboutInfo.javaDescription(version = "", vendor = "X") shouldBe null
        }

        test("systemJavaDescription reads the running JVM") { AboutInfo.systemJavaDescription().shouldNotBeNull() }
    })
