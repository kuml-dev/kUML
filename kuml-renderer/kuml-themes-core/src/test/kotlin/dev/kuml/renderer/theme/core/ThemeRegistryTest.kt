package dev.kuml.renderer.theme.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * V1.1.3 Ticket 1 — Theme registry.
 */
class ThemeRegistryTest :
    FunSpec({

        beforeEach { ThemeRegistry.clear() }
        afterSpec { ThemeRegistry.clear() }

        test("register and get by name") {
            val provider =
                object : KumlThemeProvider {
                    override val name: String = "test-theme"

                    override fun theme(): KumlTheme = PlainTheme().copy(name = "TestTheme")
                }
            ThemeRegistry.register(provider)
            val theme = ThemeRegistry.get("test-theme")
            theme shouldNotBe null
            theme!!.name shouldBe "TestTheme"
        }

        test("get returns null for unknown name") {
            ThemeRegistry.get("unknown") shouldBe null
        }

        test("loadFromClasspath finds all built-in providers (plain, kuml, elegant, playful)") {
            ThemeRegistry.loadFromClasspath()
            val names = ThemeRegistry.names()
            names shouldContain "plain"
            names shouldContain "kuml"
            names shouldContain "elegant"
            names shouldContain "playful"
        }

        test("clear empties the registry") {
            ThemeRegistry.loadFromClasspath()
            ThemeRegistry.names().isNotEmpty() shouldBe true
            ThemeRegistry.clear()
            ThemeRegistry.names().isEmpty() shouldBe true
        }
    })
