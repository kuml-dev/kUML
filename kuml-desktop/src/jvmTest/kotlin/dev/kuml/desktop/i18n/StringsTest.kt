package dev.kuml.desktop.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class StringsTest : FunSpec({

    // --- forLanguage dispatch ---
    test("forLanguage(en) returns EN") { Strings.forLanguage("en") shouldBe Strings.EN }
    test("forLanguage(de) returns DE") { Strings.forLanguage("de") shouldBe Strings.DE }
    test("forLanguage(unknown) defaults to EN") { Strings.forLanguage("fr") shouldBe Strings.EN }
    test("forLanguage(empty) defaults to EN") { Strings.forLanguage("") shouldBe Strings.EN }

    // --- EN content ---
    test("EN menuFile is 'File'")    { Strings.EN.menuFile shouldBe "File" }
    test("EN menuHelp is 'Help'")    { Strings.EN.menuHelp shouldBe "Help" }
    test("EN statusReady is 'Ready'") { Strings.EN.statusReady shouldBe "Ready" }
    test("EN statusNoDiagram is 'No diagram'") { Strings.EN.statusNoDiagram shouldBe "No diagram" }

    // --- DE content ---
    test("DE menuFile is 'Datei'")   { Strings.DE.menuFile shouldBe "Datei" }
    test("DE menuHelp is 'Hilfe'")   { Strings.DE.menuHelp shouldBe "Hilfe" }
    test("DE statusReady is 'Bereit'") { Strings.DE.statusReady shouldBe "Bereit" }
    test("DE statusNoDiagram is 'Kein Diagramm'") { Strings.DE.statusNoDiagram shouldBe "Kein Diagramm" }

    // --- EN ≠ DE ---
    test("EN and DE differ on menuFile") { Strings.EN.menuFile shouldNotBe Strings.DE.menuFile }
    test("EN and DE differ on menuEdit") { Strings.EN.menuEdit shouldNotBe Strings.DE.menuEdit }
    test("EN and DE differ on statusReady") { Strings.EN.statusReady shouldNotBe Strings.DE.statusReady }
    test("EN and DE differ on statusNoDiagram") { Strings.EN.statusNoDiagram shouldNotBe Strings.DE.statusNoDiagram }

    // --- V3.0.12 new keys (both languages non-empty) ---
    test("EN menuFileRecent is non-empty") { Strings.EN.menuFileRecent.isNotEmpty() shouldBe true }
    test("DE menuFileRecent is non-empty") { Strings.DE.menuFileRecent.isNotEmpty() shouldBe true }
    test("EN menuFileRecentEmpty is non-empty") { Strings.EN.menuFileRecentEmpty.isNotEmpty() shouldBe true }
    test("DE menuFileRecentEmpty is non-empty") { Strings.DE.menuFileRecentEmpty.isNotEmpty() shouldBe true }
    test("EN dialogUnsavedTitle is non-empty") { Strings.EN.dialogUnsavedTitle.isNotEmpty() shouldBe true }
    test("DE dialogUnsavedTitle is non-empty") { Strings.DE.dialogUnsavedTitle.isNotEmpty() shouldBe true }
    test("EN aboutTitle is non-empty") { Strings.EN.aboutTitle.isNotEmpty() shouldBe true }
    test("DE aboutTitle is non-empty") { Strings.DE.aboutTitle.isNotEmpty() shouldBe true }
})
