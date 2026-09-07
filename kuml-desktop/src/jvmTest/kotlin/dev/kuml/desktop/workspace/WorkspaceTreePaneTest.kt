package dev.kuml.desktop.workspace

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
    })
