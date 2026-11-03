package dev.kuml.desktop.ui.menu

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe

class MenuIconTintTest :
    FunSpec({
        test("macOS light uses the dark grey foreground") {
            menuIconTint(isMac = true, systemDark = false, swingColor = null, enabled = true) shouldBe Color(0xFF2B2B2B)
        }

        test("macOS dark uses the light grey foreground") {
            menuIconTint(isMac = true, systemDark = true, swingColor = null, enabled = true) shouldBe Color(0xFFE0E0E0)
        }

        test("macOS ignores a Swing colour and follows the system appearance") {
            menuIconTint(isMac = true, systemDark = false, swingColor = java.awt.Color.RED, enabled = true) shouldBe Color(0xFF2B2B2B)
        }

        test("other systems use the Swing menu foreground") {
            menuIconTint(isMac = false, systemDark = true, swingColor = java.awt.Color(10, 20, 30), enabled = true) shouldBe
                Color(10, 20, 30)
        }

        test("other systems fall back to dark grey without a Swing colour") {
            menuIconTint(isMac = false, systemDark = true, swingColor = null, enabled = true) shouldBe Color(0xFF2B2B2B)
        }

        test("disabled entries are dimmed to alpha 0.35 in every branch") {
            listOf(
                menuIconTint(isMac = true, systemDark = false, swingColor = null, enabled = false),
                menuIconTint(isMac = true, systemDark = true, swingColor = null, enabled = false),
                menuIconTint(isMac = false, systemDark = false, swingColor = java.awt.Color(10, 20, 30), enabled = false),
                menuIconTint(isMac = false, systemDark = false, swingColor = null, enabled = false),
            ).forEach { kotlin.math.abs(it.alpha - 0.35f) shouldBeLessThan 0.005f }
        }
    })
