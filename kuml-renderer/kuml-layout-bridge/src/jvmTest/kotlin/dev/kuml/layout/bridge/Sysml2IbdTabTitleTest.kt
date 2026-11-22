package dev.kuml.layout.bridge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** PAL Welle B, point 6: the IBD owner tab is never truncated; long names wrap onto at most two lines at spaces. */
class Sysml2IbdTabTitleTest :
    FunSpec({
        test("a short owner name stays on one line with the standard tab height") {
            Sysml2LayoutBridge.ibdTabLines("Vehicle") shouldBe listOf("ibd [part] Vehicle")
            Sysml2LayoutBridge.ibdTabHeight("Vehicle") shouldBe Sysml2LayoutBridge.IBD_FRAME_TAB_H_PX
        }

        test("a 41+ character title is shown in full, without an ellipsis") {
            val name = "Teilsystem E-Erhitzer-Regelung"
            val title = Sysml2LayoutBridge.ibdTabTitle(name)
            title shouldBe "ibd [part] Teilsystem E-Erhitzer-Regelung"
            title shouldNotContain "…"
            Sysml2LayoutBridge.ibdTabLines(name).joinToString(" ") shouldBe title
            val lineW = Sysml2LayoutBridge.estimateLabelSize(text = Sysml2LayoutBridge.ibdTabLines(name).first()).width
            (Sysml2LayoutBridge.ibdTabWidth(name) >= lineW) shouldBe true
        }

        test("a long name wraps onto two lines at spaces only, the first line stays within 360 px") {
            val name = "Teilsystem Druckregelung Einfrieren Personalschleuse Mixing-Bereich"
            val lines = Sysml2LayoutBridge.ibdTabLines(name)
            lines shouldHaveSize 2
            lines.first().startsWith("ibd [part]") shouldBe true
            // no break at a hyphen: the hyphenated word is never split across the lines
            ("Mixing-Bereich" in lines.joinToString(" ")) shouldBe true
            lines.joinToString(" ") shouldBe "ibd [part] $name"
            (Sysml2LayoutBridge.estimateLabelSize(text = lines.first()).width + 16f) shouldBeLessThanOrEqual 360f
            Sysml2LayoutBridge.ibdTabHeight(name) shouldBe
                (Sysml2LayoutBridge.IBD_FRAME_TAB_H_PX + 16f)
        }

        test("an over-long single word is not broken; the tab grows") {
            val name = "Überlangerbezeichnerohneleerzeichen".repeat(3)
            val lines = Sysml2LayoutBridge.ibdTabLines(name)
            lines shouldBe listOf("ibd [part]", name)
            (Sysml2LayoutBridge.ibdTabWidth(name) > 360f) shouldBe true
        }
    })
