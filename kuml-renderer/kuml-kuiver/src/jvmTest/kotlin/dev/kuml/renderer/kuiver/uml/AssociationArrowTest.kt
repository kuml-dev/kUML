package dev.kuml.renderer.kuiver.uml

import androidx.compose.ui.geometry.Offset
import dev.kuml.uml.UmlNavigability
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AssociationArrowTest {
    @Test
    fun `BOTH and NEITHER draw no arrow`() {
        val s = Offset(x = 0f, y = 0f)
        val t = Offset(x = 10f, y = 0f)
        associationArrow(nav = UmlNavigability.BOTH, source = s, target = t) shouldBe null
        associationArrow(nav = UmlNavigability.NEITHER, source = s, target = t) shouldBe null
    }

    @Test
    fun `TARGET_ONLY draws arrow toward target unchanged`() {
        val s = Offset(x = 0f, y = 0f)
        val t = Offset(x = 10f, y = 0f)
        associationArrow(nav = UmlNavigability.TARGET_ONLY, source = s, target = t) shouldBe (s to t)
    }

    @Test
    fun `SOURCE_ONLY reverses the pair so the arrowhead lands at source`() {
        val s = Offset(x = 0f, y = 0f)
        val t = Offset(x = 10f, y = 0f)
        associationArrow(nav = UmlNavigability.SOURCE_ONLY, source = s, target = t) shouldBe (t to s)
    }
}
