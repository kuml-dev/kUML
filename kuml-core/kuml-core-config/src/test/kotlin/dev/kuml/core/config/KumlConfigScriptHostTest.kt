package dev.kuml.core.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * V1.1.3 Ticket 1 — Config script host and extractor.
 */
class KumlConfigScriptHostTest :
    FunSpec({

        test("inline config script with kumlConfig returns KumlConfig") {
            val result =
                KumlConfigScriptHost.eval(
                    code =
                        """
                        kumlConfig {
                            render {
                                themes { default = "kuml" }
                            }
                        }
                        """.trimIndent(),
                )
            val success = result as ResultWithDiagnostics.Success
            val cfg =
                ConfigExtractor.extract(
                    success.value.returnValue,
                    java.io.File("inline.kuml.config.kts"),
                )
            cfg.render.themeName shouldBe "kuml"
        }

        test("script that does not end with kumlConfig produces clear error") {
            // The script returns a String, which ConfigExtractor will reject.
            val result =
                KumlConfigScriptHost.eval(code = """ "not a config" """)
            val success = result as ResultWithDiagnostics.Success
            val ex =
                shouldThrow<IllegalStateException> {
                    ConfigExtractor.extract(
                        success.value.returnValue,
                        java.io.File("inline.kuml.config.kts"),
                    )
                }
            ex.message!! shouldContain "kumlConfig"
        }

        test("script with syntax error reports ERROR diagnostic") {
            val result = KumlConfigScriptHost.eval(code = "this is not valid kotlin!!!")
            result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }.shouldNotBeEmpty()
        }

        test("inline script with kumlConfig + stereotypes parses to override patch") {
            val result =
                KumlConfigScriptHost.eval(
                    code =
                        """
                        kumlConfig {
                            render {
                                stereotypes {
                                    showFeatureStereotypes = false
                                    joinSeparator = " | "
                                }
                            }
                        }
                        """.trimIndent(),
                )
            val success = result as ResultWithDiagnostics.Success
            val cfg =
                ConfigExtractor.extract(
                    success.value.returnValue,
                    java.io.File("inline.kuml.config.kts"),
                )
            cfg.render.stereotypeOverrides!!.showFeatureStereotypes shouldBe false
            cfg.render.stereotypeOverrides!!.joinSeparator shouldBe " | "
        }
    })
