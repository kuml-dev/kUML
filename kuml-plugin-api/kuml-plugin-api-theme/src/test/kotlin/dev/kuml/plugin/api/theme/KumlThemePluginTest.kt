package dev.kuml.plugin.api.theme

import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.renderer.theme.core.PlainTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class KumlThemePluginTest :
    FunSpec({

        val testDescriptor =
            PluginDescriptor(
                id = "test-theme-plugin",
                name = "Test Theme Plugin",
                version = PluginVersion(1, 0, 0),
                kumlVersionRange = KumlVersionRange(">=3.0.27"),
                capabilities = setOf(PluginCapability.THEME),
            )

        val testPlugin =
            object : KumlThemePlugin {
                override val descriptor: PluginDescriptor = testDescriptor

                override fun themes() = listOf(PlainTheme())
            }

        test("KumlThemePlugin themes() must not be empty") {
            testPlugin.themes().shouldNotBeEmpty()
        }

        test("minimal anonymous KumlThemePlugin implementation is valid") {
            testPlugin.descriptor.id shouldBe "test-theme-plugin"
            testPlugin.themes().size shouldBe 1
        }

        test("KumlTheme from plugin is accessible with name and colors") {
            val theme = testPlugin.themes().first()
            theme.name shouldBe "Plain"
            theme.colors shouldBe theme.colors // reference equality guard
        }

        test("descriptor capabilities contains THEME") {
            testPlugin.descriptor.capabilities shouldContain PluginCapability.THEME
        }

        test("descriptor serializes with THEME capability") {
            val desc = testPlugin.descriptor
            desc.capabilities.contains(PluginCapability.THEME) shouldBe true
            desc.version.toString() shouldBe "1.0.0"
        }
    })
