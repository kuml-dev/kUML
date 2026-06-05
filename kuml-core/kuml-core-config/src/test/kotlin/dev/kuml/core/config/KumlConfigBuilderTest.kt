package dev.kuml.core.config

import dev.kuml.renderer.theme.core.StereotypeTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * V1.1.3 Ticket 1 — Config-DSL builders.
 */
class KumlConfigBuilderTest :
    FunSpec({

        test("kumlConfig with empty body returns DEFAULT") {
            val c = kumlConfig { }
            c shouldBe KumlConfig.DEFAULT
        }

        test("themes.default sets render.themeName") {
            val c =
                kumlConfig {
                    render {
                        themes { default = "kuml" }
                    }
                }
            c.render.themeName shouldBe "kuml"
        }

        test("stereotypes block produces sparse patch with only set fields") {
            val c =
                kumlConfig {
                    render {
                        stereotypes {
                            showTaggedValues = true
                            joinSeparator = " | "
                        }
                    }
                }
            val patch = c.render.stereotypeOverrides!!
            patch.showTaggedValues shouldBe true
            patch.joinSeparator shouldBe " | "
            // Other fields stay null — sparse patch
            patch.showFeatureStereotypes shouldBe null
            patch.headerFontSize shouldBe null
        }

        test("merge layering: override beats base for non-null fields") {
            val base =
                KumlConfig(
                    render =
                        RenderConfig(
                            themeName = "plain",
                            stereotypeOverrides = StereotypeOverridePatch(showTaggedValues = false),
                        ),
                )
            val override = KumlConfig(render = RenderConfig(themeName = "kuml"))
            val merged = base.merge(override)
            merged.render.themeName shouldBe "kuml"
            // Override has no stereotypeOverrides; base's value carries over.
            merged.render.stereotypeOverrides shouldBe StereotypeOverridePatch(showTaggedValues = false)
        }

        test("StereotypeOverridePatch.applyTo preserves base defaults for unset slots") {
            val base =
                StereotypeTheme(
                    showTaggedValues = false,
                    joinSeparator = ", ",
                    showFeatureStereotypes = true,
                )
            val patch = StereotypeOverridePatch(showTaggedValues = true)
            val merged = patch.applyTo(base)
            merged.showTaggedValues shouldBe true
            // joinSeparator and showFeatureStereotypes come from base
            merged.joinSeparator shouldBe ", "
            merged.showFeatureStereotypes shouldBe true
        }

        test("multiple stereotypes blocks accumulate on the same builder") {
            val c =
                kumlConfig {
                    render {
                        stereotypes { showTaggedValues = true }
                        stereotypes { joinSeparator = " | " }
                    }
                }
            val patch = c.render.stereotypeOverrides!!
            patch.showTaggedValues shouldBe true
            patch.joinSeparator shouldBe " | "
        }
    })
