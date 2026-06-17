package dev.kuml.plugin.examples.pdv

import dev.kuml.plugin.api.core.PluginCapability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PdvThemePluginTest :
    FunSpec({

        val plugin = PdvThemePlugin()

        test("themes() returns exactly 2 themes") {
            plugin.themes() shouldHaveSize 2
        }

        test("themes() contains pdv-light") {
            val names = plugin.themes().map { it.name }
            names shouldContain "pdv-light"
        }

        test("themes() contains pdv-dark") {
            val names = plugin.themes().map { it.name }
            names shouldContain "pdv-dark"
        }

        test("pdv-light background is white") {
            val light = plugin.themes().first { it.name == "pdv-light" }
            light.colors.background shouldBe PdvThemePlugin.WHITE
        }

        test("pdv-light accent is AUREOLIN") {
            val light = plugin.themes().first { it.name == "pdv-light" }
            light.colors.accent shouldBe PdvThemePlugin.AUREOLIN
        }

        test("pdv-dark background is BISCAY") {
            val dark = plugin.themes().first { it.name == "pdv-dark" }
            dark.colors.background shouldBe PdvThemePlugin.BISCAY
        }

        test("descriptor id is dev.kuml.plugin.theme.pdv") {
            plugin.descriptor.id shouldBe "dev.kuml.plugin.theme.pdv"
        }

        test("descriptor capabilities contains THEME") {
            plugin.descriptor.capabilities shouldContain PluginCapability.THEME
        }

        test("descriptor requiredPermissions is empty — theme needs no permissions") {
            plugin.descriptor.requiredPermissions shouldBe emptySet()
        }

        test("both themes use Inter typography") {
            for (theme in plugin.themes()) {
                theme.typography.title.family shouldBe "Inter"
                theme.typography.subtitle.family shouldBe "Inter"
                theme.typography.body.family shouldBe "Inter"
                theme.typography.stereotype.italic shouldBe true
            }
        }

        test("AUREOLIN color has correct hex value") {
            PdvThemePlugin.AUREOLIN.toHex() shouldBe "#FFED00"
        }

        test("kumlVersionRange is non-null") {
            plugin.descriptor.kumlVersionRange shouldNotBe null
        }
    })
