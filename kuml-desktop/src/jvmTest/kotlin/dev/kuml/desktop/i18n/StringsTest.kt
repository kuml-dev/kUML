package dev.kuml.desktop.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class StringsTest :
    FunSpec({

        // --- forLanguage dispatch ---
        test("forLanguage(en) returns EN") { Strings.forLanguage("en") shouldBe Strings.EN }
        test("forLanguage(de) returns DE") { Strings.forLanguage("de") shouldBe Strings.DE }
        test("forLanguage(unknown) defaults to EN") { Strings.forLanguage("fr") shouldBe Strings.EN }
        test("forLanguage(empty) defaults to EN") { Strings.forLanguage("") shouldBe Strings.EN }

        // --- EN content ---
        test("EN menuFile is 'File'") { Strings.EN.menuFile shouldBe "File" }
        test("EN menuHelp is 'Help'") { Strings.EN.menuHelp shouldBe "Help" }
        test("EN statusReady is 'Ready'") { Strings.EN.statusReady shouldBe "Ready" }
        test("EN statusNoDiagram is 'No diagram'") { Strings.EN.statusNoDiagram shouldBe "No diagram" }

        // --- DE content ---
        test("DE menuFile is 'Datei'") { Strings.DE.menuFile shouldBe "Datei" }
        test("DE menuHelp is 'Hilfe'") { Strings.DE.menuHelp shouldBe "Hilfe" }
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

        // --- V3.6.4 — Knowledge Workspace viewer keys (both languages non-empty, EN ≠ DE) ---
        test("EN menuFileOpenWorkspace is non-empty") { Strings.EN.menuFileOpenWorkspace.isNotEmpty() shouldBe true }
        test("DE menuFileOpenWorkspace is non-empty") { Strings.DE.menuFileOpenWorkspace.isNotEmpty() shouldBe true }
        test("EN and DE differ on menuFileOpenWorkspace") { Strings.EN.menuFileOpenWorkspace shouldNotBe Strings.DE.menuFileOpenWorkspace }

        test("EN workspaceTrustTitle is non-empty") { Strings.EN.workspaceTrustTitle.isNotEmpty() shouldBe true }
        test("DE workspaceTrustTitle is non-empty") { Strings.DE.workspaceTrustTitle.isNotEmpty() shouldBe true }
        test("EN and DE differ on workspaceTrustTitle") { Strings.EN.workspaceTrustTitle shouldNotBe Strings.DE.workspaceTrustTitle }

        test("EN workspaceTrustMessage contains a %s placeholder for the root path") {
            Strings.EN.workspaceTrustMessage shouldContain "%s"
        }
        test("DE workspaceTrustMessage contains a %s placeholder for the root path") {
            Strings.DE.workspaceTrustMessage shouldContain "%s"
        }

        test("EN previewErmUnsupported is non-empty") { Strings.EN.previewErmUnsupported.isNotEmpty() shouldBe true }
        test("DE previewErmUnsupported is non-empty") { Strings.DE.previewErmUnsupported.isNotEmpty() shouldBe true }
        test("EN and DE differ on previewErmUnsupported") { Strings.EN.previewErmUnsupported shouldNotBe Strings.DE.previewErmUnsupported }

        test("EN previewNotTrusted is non-empty") { Strings.EN.previewNotTrusted.isNotEmpty() shouldBe true }
        test("DE previewNotTrusted is non-empty") { Strings.DE.previewNotTrusted.isNotEmpty() shouldBe true }

        test("EN workspaceUnknownMessage is non-empty") { Strings.EN.workspaceUnknownMessage.isNotEmpty() shouldBe true }
        test("DE workspaceUnknownMessage is non-empty") { Strings.DE.workspaceUnknownMessage.isNotEmpty() shouldBe true }

        // --- Workspace tree type-badge tooltips (retroactive UI/UX-team review) ---
        test("EN workspaceBadgeDiagram is non-empty") { Strings.EN.workspaceBadgeDiagram.isNotEmpty() shouldBe true }
        test("DE workspaceBadgeDiagram is non-empty") { Strings.DE.workspaceBadgeDiagram.isNotEmpty() shouldBe true }
        test("EN and DE differ on workspaceBadgeDiagram") { Strings.EN.workspaceBadgeDiagram shouldNotBe Strings.DE.workspaceBadgeDiagram }

        test("EN workspaceBadgeProse is non-empty") { Strings.EN.workspaceBadgeProse.isNotEmpty() shouldBe true }
        test("DE workspaceBadgeProse is non-empty") { Strings.DE.workspaceBadgeProse.isNotEmpty() shouldBe true }
        test("EN and DE differ on workspaceBadgeProse") { Strings.EN.workspaceBadgeProse shouldNotBe Strings.DE.workspaceBadgeProse }

        test("EN workspaceBadgeUnknown is non-empty") { Strings.EN.workspaceBadgeUnknown.isNotEmpty() shouldBe true }
        test("DE workspaceBadgeUnknown is non-empty") { Strings.DE.workspaceBadgeUnknown.isNotEmpty() shouldBe true }
        test("EN and DE differ on workspaceBadgeUnknown") { Strings.EN.workspaceBadgeUnknown shouldNotBe Strings.DE.workspaceBadgeUnknown }

        test("EN workspaceBacklinksLabel is non-empty") { Strings.EN.workspaceBacklinksLabel.isNotEmpty() shouldBe true }
        test("DE workspaceBacklinksLabel is non-empty") { Strings.DE.workspaceBacklinksLabel.isNotEmpty() shouldBe true }
        test("EN and DE differ on workspaceBacklinksLabel") { Strings.EN.workspaceBacklinksLabel shouldNotBe Strings.DE.workspaceBacklinksLabel }
    })
