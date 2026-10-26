package dev.kuml.desktop.simulation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SidePanelWidthTest :
    FunSpec({
        test("widths inside the range are kept") {
            clampSidePanelWidth(340f) shouldBe 340f
            clampSidePanelWidth(SIDE_PANEL_MIN_DP) shouldBe SIDE_PANEL_MIN_DP
            clampSidePanelWidth(SIDE_PANEL_MAX_DP) shouldBe SIDE_PANEL_MAX_DP
        }

        test("widths outside the range are clamped") {
            clampSidePanelWidth(10f) shouldBe 280f
            clampSidePanelWidth(9999f) shouldBe 520f
            clampSidePanelWidth(Float.NEGATIVE_INFINITY) shouldBe 280f
            clampSidePanelWidth(Float.POSITIVE_INFINITY) shouldBe 520f
        }

        test("NaN falls back to the default width") {
            clampSidePanelWidth(Float.NaN) shouldBe SIDE_PANEL_DEFAULT_DP
        }
    })
