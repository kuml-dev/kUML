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
        test("EN and DE differ on workspaceBacklinksLabel") {
            Strings.EN.workspaceBacklinksLabel shouldNotBe
                Strings.DE.workspaceBacklinksLabel
        }

        // --- Design-review pass: Export (P3), dialogClose (P6), Plugin Manager i18n (P5),
        //     AI input placeholder (P5) ---

        test("EN menuFileExportSvg is non-empty") { Strings.EN.menuFileExportSvg.isNotEmpty() shouldBe true }
        test("DE menuFileExportSvg is non-empty") { Strings.DE.menuFileExportSvg.isNotEmpty() shouldBe true }
        test("EN and DE differ on menuFileExportSvg") { Strings.EN.menuFileExportSvg shouldNotBe Strings.DE.menuFileExportSvg }

        test("EN menuFileExportPng is non-empty") { Strings.EN.menuFileExportPng.isNotEmpty() shouldBe true }
        test("DE menuFileExportPng is non-empty") { Strings.DE.menuFileExportPng.isNotEmpty() shouldBe true }

        test("EN dialogExportTitle is non-empty") { Strings.EN.dialogExportTitle.isNotEmpty() shouldBe true }
        test("DE dialogExportTitle is non-empty") { Strings.DE.dialogExportTitle.isNotEmpty() shouldBe true }

        test("EN dialogClose is non-empty") { Strings.EN.dialogClose.isNotEmpty() shouldBe true }
        test("DE dialogClose is non-empty") { Strings.DE.dialogClose.isNotEmpty() shouldBe true }
        test("EN and DE differ on dialogClose") { Strings.EN.dialogClose shouldNotBe Strings.DE.dialogClose }

        test("EN pluginManagerHeadline is non-empty") { Strings.EN.pluginManagerHeadline.isNotEmpty() shouldBe true }
        test("DE pluginManagerHeadline is non-empty") { Strings.DE.pluginManagerHeadline.isNotEmpty() shouldBe true }
        test("EN and DE differ on pluginManagerHeadline") {
            Strings.EN.pluginManagerHeadline shouldNotBe Strings.DE.pluginManagerHeadline
        }

        test("EN pluginManagerTabTransformers is 'Transformers'") {
            Strings.EN.pluginManagerTabTransformers shouldBe "Transformers"
        }
        test("DE pluginManagerTabTransformers is 'Transformer'") {
            Strings.DE.pluginManagerTabTransformers shouldBe "Transformer"
        }

        test("EN pluginManagerRegistryUnreachable is non-empty") {
            Strings.EN.pluginManagerRegistryUnreachable.isNotEmpty() shouldBe true
        }
        test("DE pluginManagerRegistryUnreachable is non-empty") {
            Strings.DE.pluginManagerRegistryUnreachable.isNotEmpty() shouldBe true
        }

        test("EN pluginManagerShowAllReviews contains a %d placeholder") {
            Strings.EN.pluginManagerShowAllReviews shouldContain "%d"
        }
        test("DE pluginManagerShowAllReviews contains a %d placeholder") {
            Strings.DE.pluginManagerShowAllReviews shouldContain "%d"
        }

        test("EN aiInputPlaceholder is non-empty") { Strings.EN.aiInputPlaceholder.isNotEmpty() shouldBe true }
        test("DE aiInputPlaceholder is non-empty") { Strings.DE.aiInputPlaceholder.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiInputPlaceholder") {
            Strings.EN.aiInputPlaceholder shouldNotBe Strings.DE.aiInputPlaceholder
        }

        // --- V3.7.1 — AI provider settings (both languages non-empty; EN vs DE parity) ---
        test("EN menuAiProviderSettings is non-empty") { Strings.EN.menuAiProviderSettings.isNotEmpty() shouldBe true }
        test("DE menuAiProviderSettings is non-empty") { Strings.DE.menuAiProviderSettings.isNotEmpty() shouldBe true }
        test("EN and DE differ on menuAiProviderSettings") {
            Strings.EN.menuAiProviderSettings shouldNotBe Strings.DE.menuAiProviderSettings
        }

        test("EN aiProviderSettingsHeadline is non-empty") { Strings.EN.aiProviderSettingsHeadline.isNotEmpty() shouldBe true }
        test("DE aiProviderSettingsHeadline is non-empty") { Strings.DE.aiProviderSettingsHeadline.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiProviderSettingsHeadline") {
            Strings.EN.aiProviderSettingsHeadline shouldNotBe Strings.DE.aiProviderSettingsHeadline
        }

        test("EN aiManageProviders is non-empty") { Strings.EN.aiManageProviders.isNotEmpty() shouldBe true }
        test("DE aiManageProviders is non-empty") { Strings.DE.aiManageProviders.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiManageProviders") {
            Strings.EN.aiManageProviders shouldNotBe Strings.DE.aiManageProviders
        }

        test("EN aiPrivacyModeLabel is non-empty") { Strings.EN.aiPrivacyModeLabel.isNotEmpty() shouldBe true }
        test("DE aiPrivacyModeLabel is non-empty") { Strings.DE.aiPrivacyModeLabel.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiPrivacyModeLabel") {
            Strings.EN.aiPrivacyModeLabel shouldNotBe Strings.DE.aiPrivacyModeLabel
        }

        test("EN aiPrivacyModeHint is non-empty") { Strings.EN.aiPrivacyModeHint.isNotEmpty() shouldBe true }
        test("DE aiPrivacyModeHint is non-empty") { Strings.DE.aiPrivacyModeHint.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiPrivacyModeHint") {
            Strings.EN.aiPrivacyModeHint shouldNotBe Strings.DE.aiPrivacyModeHint
        }

        test("EN aiPrivacyConfirmTitle is non-empty") { Strings.EN.aiPrivacyConfirmTitle.isNotEmpty() shouldBe true }
        test("DE aiPrivacyConfirmTitle is non-empty") { Strings.DE.aiPrivacyConfirmTitle.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiPrivacyConfirmTitle") {
            Strings.EN.aiPrivacyConfirmTitle shouldNotBe Strings.DE.aiPrivacyConfirmTitle
        }

        // The confirmation body must name the concrete consequence (data leaving the machine),
        // never a generic "Are you sure?" — verified here so a future edit can't silently regress it.
        test("EN aiPrivacyConfirmBody names the provider explicitly") {
            Strings.EN.aiPrivacyConfirmBody shouldContain "provider"
        }
        test("DE aiPrivacyConfirmBody names the provider explicitly") {
            Strings.DE.aiPrivacyConfirmBody shouldContain "Anbieter"
        }
        test("EN and DE differ on aiPrivacyConfirmBody") {
            Strings.EN.aiPrivacyConfirmBody shouldNotBe Strings.DE.aiPrivacyConfirmBody
        }

        test("EN aiPrivacyConfirmAccept is non-empty") { Strings.EN.aiPrivacyConfirmAccept.isNotEmpty() shouldBe true }
        test("DE aiPrivacyConfirmAccept is non-empty") { Strings.DE.aiPrivacyConfirmAccept.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiPrivacyConfirmAccept") {
            Strings.EN.aiPrivacyConfirmAccept shouldNotBe Strings.DE.aiPrivacyConfirmAccept
        }

        test("EN aiPrivacyConfirmCancel is non-empty") { Strings.EN.aiPrivacyConfirmCancel.isNotEmpty() shouldBe true }
        test("DE aiPrivacyConfirmCancel is non-empty") { Strings.DE.aiPrivacyConfirmCancel.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiPrivacyConfirmCancel") {
            Strings.EN.aiPrivacyConfirmCancel shouldNotBe Strings.DE.aiPrivacyConfirmCancel
        }

        test("EN aiPrivacyBadge is non-empty") { Strings.EN.aiPrivacyBadge.isNotEmpty() shouldBe true }
        test("DE aiPrivacyBadge is non-empty") { Strings.DE.aiPrivacyBadge.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiPrivacyBadge") {
            Strings.EN.aiPrivacyBadge shouldNotBe Strings.DE.aiPrivacyBadge
        }

        test("EN aiProviderLocal is non-empty") { Strings.EN.aiProviderLocal.isNotEmpty() shouldBe true }
        test("DE aiProviderLocal is non-empty") { Strings.DE.aiProviderLocal.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiProviderLocal") {
            Strings.EN.aiProviderLocal shouldNotBe Strings.DE.aiProviderLocal
        }

        // aiProviderCloud is deliberately identical in both languages ("Cloud") — no
        // shouldNotBe parity test for this key, per the V3.7.1 plan (F6/§D).
        test("EN aiProviderCloud is non-empty") { Strings.EN.aiProviderCloud.isNotEmpty() shouldBe true }
        test("DE aiProviderCloud is non-empty") { Strings.DE.aiProviderCloud.isNotEmpty() shouldBe true }

        test("EN aiProviderNoModels is non-empty") { Strings.EN.aiProviderNoModels.isNotEmpty() shouldBe true }
        test("DE aiProviderNoModels is non-empty") { Strings.DE.aiProviderNoModels.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiProviderNoModels") {
            Strings.EN.aiProviderNoModels shouldNotBe Strings.DE.aiProviderNoModels
        }

        test("EN aiProviderNeedsKey is non-empty") { Strings.EN.aiProviderNeedsKey.isNotEmpty() shouldBe true }
        test("DE aiProviderNeedsKey is non-empty") { Strings.DE.aiProviderNeedsKey.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiProviderNeedsKey") {
            Strings.EN.aiProviderNeedsKey shouldNotBe Strings.DE.aiProviderNeedsKey
        }

        test("EN aiProviderBlockedByPrivacy is non-empty") { Strings.EN.aiProviderBlockedByPrivacy.isNotEmpty() shouldBe true }
        test("DE aiProviderBlockedByPrivacy is non-empty") { Strings.DE.aiProviderBlockedByPrivacy.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiProviderBlockedByPrivacy") {
            Strings.EN.aiProviderBlockedByPrivacy shouldNotBe Strings.DE.aiProviderBlockedByPrivacy
        }

        test("EN aiKeySave is non-empty") { Strings.EN.aiKeySave.isNotEmpty() shouldBe true }
        test("DE aiKeySave is non-empty") { Strings.DE.aiKeySave.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiKeySave") { Strings.EN.aiKeySave shouldNotBe Strings.DE.aiKeySave }

        test("EN aiKeyChange is non-empty") { Strings.EN.aiKeyChange.isNotEmpty() shouldBe true }
        test("DE aiKeyChange is non-empty") { Strings.DE.aiKeyChange.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiKeyChange") { Strings.EN.aiKeyChange shouldNotBe Strings.DE.aiKeyChange }

        test("EN aiKeyDelete is non-empty") { Strings.EN.aiKeyDelete.isNotEmpty() shouldBe true }
        test("DE aiKeyDelete is non-empty") { Strings.DE.aiKeyDelete.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiKeyDelete") { Strings.EN.aiKeyDelete shouldNotBe Strings.DE.aiKeyDelete }

        test("EN aiKeyPlaceholder is non-empty") { Strings.EN.aiKeyPlaceholder.isNotEmpty() shouldBe true }
        test("DE aiKeyPlaceholder is non-empty") { Strings.DE.aiKeyPlaceholder.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiKeyPlaceholder") {
            Strings.EN.aiKeyPlaceholder shouldNotBe Strings.DE.aiKeyPlaceholder
        }

        test("EN aiVaultPlainWarning is non-empty") { Strings.EN.aiVaultPlainWarning.isNotEmpty() shouldBe true }
        test("DE aiVaultPlainWarning is non-empty") { Strings.DE.aiVaultPlainWarning.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiVaultPlainWarning") {
            Strings.EN.aiVaultPlainWarning shouldNotBe Strings.DE.aiVaultPlainWarning
        }

        test("EN aiDefaultProvider is non-empty") { Strings.EN.aiDefaultProvider.isNotEmpty() shouldBe true }
        test("DE aiDefaultProvider is non-empty") { Strings.DE.aiDefaultProvider.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiDefaultProvider") {
            Strings.EN.aiDefaultProvider shouldNotBe Strings.DE.aiDefaultProvider
        }

        test("EN aiDefaultModel is non-empty") { Strings.EN.aiDefaultModel.isNotEmpty() shouldBe true }
        test("DE aiDefaultModel is non-empty") { Strings.DE.aiDefaultModel.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiDefaultModel") {
            Strings.EN.aiDefaultModel shouldNotBe Strings.DE.aiDefaultModel
        }

        // --- V3.7.4 new keys (both languages non-empty, EN ≠ DE) ---
        test("EN aiInputHint is non-empty") { Strings.EN.aiInputHint.isNotEmpty() shouldBe true }
        test("DE aiInputHint is non-empty") { Strings.DE.aiInputHint.isNotEmpty() shouldBe true }
        test("EN and DE differ on aiInputHint") { Strings.EN.aiInputHint shouldNotBe Strings.DE.aiInputHint }

        test("EN aiVaultLegacyKeychainNotice is non-empty") {
            Strings.EN.aiVaultLegacyKeychainNotice.isNotEmpty() shouldBe true
        }
        test("DE aiVaultLegacyKeychainNotice is non-empty") {
            Strings.DE.aiVaultLegacyKeychainNotice.isNotEmpty() shouldBe true
        }
        test("EN and DE differ on aiVaultLegacyKeychainNotice") {
            Strings.EN.aiVaultLegacyKeychainNotice shouldNotBe Strings.DE.aiVaultLegacyKeychainNotice
        }

        test("EN menuViewWatermark is non-empty") { Strings.EN.menuViewWatermark.isNotEmpty() shouldBe true }
        test("DE menuViewWatermark is non-empty") { Strings.DE.menuViewWatermark.isNotEmpty() shouldBe true }
        test("EN and DE differ on menuViewWatermark") {
            Strings.EN.menuViewWatermark shouldNotBe Strings.DE.menuViewWatermark
        }

        test("EN findPlaceholder is non-empty") { Strings.EN.findPlaceholder.isNotEmpty() shouldBe true }
        test("DE findPlaceholder is non-empty") { Strings.DE.findPlaceholder.isNotEmpty() shouldBe true }

        test("EN findNext is non-empty") { Strings.EN.findNext.isNotEmpty() shouldBe true }
        test("DE findNext is non-empty") { Strings.DE.findNext.isNotEmpty() shouldBe true }
        test("EN and DE differ on findNext") { Strings.EN.findNext shouldNotBe Strings.DE.findNext }

        test("EN findPrevious is non-empty") { Strings.EN.findPrevious.isNotEmpty() shouldBe true }
        test("DE findPrevious is non-empty") { Strings.DE.findPrevious.isNotEmpty() shouldBe true }
        test("EN and DE differ on findPrevious") { Strings.EN.findPrevious shouldNotBe Strings.DE.findPrevious }

        test("EN findClose is non-empty") { Strings.EN.findClose.isNotEmpty() shouldBe true }
        test("DE findClose is non-empty") { Strings.DE.findClose.isNotEmpty() shouldBe true }

        test("EN findMatchCase is non-empty") { Strings.EN.findMatchCase.isNotEmpty() shouldBe true }
        test("DE findMatchCase is non-empty") { Strings.DE.findMatchCase.isNotEmpty() shouldBe true }
        test("EN and DE differ on findMatchCase") { Strings.EN.findMatchCase shouldNotBe Strings.DE.findMatchCase }

        test("EN findNoMatch is non-empty") { Strings.EN.findNoMatch.isNotEmpty() shouldBe true }
        test("DE findNoMatch is non-empty") { Strings.DE.findNoMatch.isNotEmpty() shouldBe true }
        test("EN and DE differ on findNoMatch") { Strings.EN.findNoMatch shouldNotBe Strings.DE.findNoMatch }
    })
