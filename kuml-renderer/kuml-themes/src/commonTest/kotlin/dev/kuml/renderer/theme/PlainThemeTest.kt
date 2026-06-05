package dev.kuml.renderer.theme

import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class PlainThemeTest {

    @Test
    fun `PlainTheme provides non-null instances for all theme slots`() {
        // colors
        PlainTheme.colors shouldNotBe null
        PlainTheme.colors.background shouldNotBe null
        PlainTheme.colors.foreground shouldNotBe null
        PlainTheme.colors.border shouldNotBe null
        PlainTheme.colors.muted shouldNotBe null
        PlainTheme.colors.accent shouldNotBe null
        PlainTheme.colors.edge shouldNotBe null
        PlainTheme.colors.edgeMuted shouldNotBe null

        // typography
        PlainTheme.typography shouldNotBe null
        PlainTheme.typography.title shouldNotBe null
        PlainTheme.typography.subtitle shouldNotBe null
        PlainTheme.typography.body shouldNotBe null
        PlainTheme.typography.small shouldNotBe null
        PlainTheme.typography.stereotype shouldNotBe null

        // borders
        PlainTheme.borders shouldNotBe null
        PlainTheme.borders.thin shouldNotBe null
        PlainTheme.borders.regular shouldNotBe null
        PlainTheme.borders.thick shouldNotBe null
        PlainTheme.borders.cornerRadius shouldNotBe null
    }
}
