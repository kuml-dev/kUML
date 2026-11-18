package dev.kuml.desktop.workspace

import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.OkfType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [badgeTypeFor], the pure decision extracted from [WorkspaceTreePane]'s tree
 * row rendering (bugfix, review finding — see [badgeTypeFor]'s KDoc). `kuml-desktop` has no
 * Compose test harness, so this is the only way the three-way [PendingType] decision was
 * testable at all; before the extraction it was inline in a `@Composable`.
 */
class WorkspaceTreePaneTest :
    FunSpec({

        test("unselected row always shows its own saved type, regardless of any pending override") {
            badgeTypeFor(
                docType = OkfType.CONCEPT,
                isSelected = false,
                pending = PendingType.Override(type = OkfType.UML_CLASS_DIAGRAM),
            ) shouldBe OkfType.CONCEPT

            badgeTypeFor(
                docType = OkfType.CONCEPT,
                isSelected = false,
                pending = PendingType.Override(type = null),
            ) shouldBe OkfType.CONCEPT
        }

        test("selected row with NoOverride shows its own saved type -- nothing loaded into the buffer yet") {
            badgeTypeFor(
                docType = OkfType.CONCEPT,
                isSelected = true,
                pending = PendingType.NoOverride,
            ) shouldBe OkfType.CONCEPT
        }

        test("selected row with a resolved Override shows the buffer's live type, not the saved one") {
            badgeTypeFor(
                docType = OkfType.CONCEPT,
                isSelected = true,
                pending = PendingType.Override(type = OkfType.UML_CLASS_DIAGRAM),
            ) shouldBe OkfType.UML_CLASS_DIAGRAM
        }

        test(
            "selected row with Override(null) shows the '?' badge (null), even though the document " +
                "was last saved with a known type -- regression, review finding: this case used to " +
                "collapse onto docType via a bare `OkfType?`, so an unrecognised live `type:` value " +
                "(e.g. `type: Quatsch`) kept showing the stale saved badge instead of '?'",
        ) {
            badgeTypeFor(
                docType = OkfType.CONCEPT,
                isSelected = true,
                pending = PendingType.Override(type = null),
            ) shouldBe null
        }

        test("tooltip names the canonical type for a diagram, also when the file uses an alias") {
            val alias = badgeTooltip(type = OkfType.fromId("Sysml2Bdd"), rawType = "Sysml2Bdd", strings = Strings.EN)
            alias shouldBe "Contains a diagram (Sysml2BlockDefinition)"
            val german = badgeTooltip(type = OkfType.UML_CLASS_DIAGRAM, rawType = "UmlClassDiagram", strings = Strings.DE)
            german shouldBe "Enthält ein Diagramm (UmlClassDiagram)"
        }

        test("tooltip quotes the raw value of an unknown type; no type field keeps the generic text") {
            badgeTooltip(type = null, rawType = "Quatsch", strings = Strings.EN) shouldBe "Unknown document type \u201cQuatsch\u201d"
            badgeTooltip(type = null, rawType = null, strings = Strings.EN) shouldBe Strings.EN.workspaceBadgeUnknown
            badgeTooltip(type = OkfType.CONCEPT, rawType = "Concept", strings = Strings.EN) shouldBe Strings.EN.workspaceBadgeProse
        }

        test("the raw type of the selected row comes from the live buffer override") {
            val live = PendingType.Override(type = null, raw = "Quatsch")
            badgeRawTypeFor(docRawType = "Concept", isSelected = true, pending = live) shouldBe "Quatsch"
            badgeRawTypeFor(docRawType = "Concept", isSelected = false, pending = live) shouldBe "Concept"
            badgeRawTypeFor(docRawType = "Concept", isSelected = true, pending = PendingType.NoOverride) shouldBe "Concept"
        }

        test("the type dropdown shows the canonical id for an alias, the raw value plus suffix for unknown, a dash for none") {
            typeDropdownLabel(rawType = "Sysml2Bdd", customSuffix = "custom") shouldBe "Sysml2BlockDefinition"
            typeDropdownLabel(rawType = "Concept", customSuffix = "custom") shouldBe "Concept"
            typeDropdownLabel(rawType = "Quatsch", customSuffix = "custom") shouldBe "Quatsch (custom)"
            typeDropdownLabel(rawType = null, customSuffix = "custom") shouldBe "\u2014"
        }
    })
