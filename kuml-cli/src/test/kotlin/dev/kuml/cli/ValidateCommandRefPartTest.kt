package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/** Nacharbeit 53, P-BDD-05: `kuml validate` rejects a `refPart` whose type is no part definition. */
class ValidateCommandRefPartTest :
    FunSpec({
        fun validate(script: String): Pair<Int, String> {
            val f = File.createTempFile("kuml-refpart-", ".kuml.kts")
            try {
                f.writeText(script)
                val r = KumlCli().test(listOf("validate", f.absolutePath))
                return r.statusCode to r.output
            } finally {
                f.delete()
            }
        }

        test("a mistyped refPart type exits non-zero and names the code") {
            val (status, out) =
                validate(
                    """
                    sysml2Model(name = "R") {
                        partDef(name = "Gateway")
                        val ctl = partDef(name = "Controller") { refPart(name = "gw", typeId = "Gatewya") }
                        bdd(name = "D") { include(ctl) }
                    }
                    """.trimIndent(),
                )
            status shouldNotBe 0
            out shouldContain "SYSML2_REFPART_TYPE_UNKNOWN"
            out shouldContain "Gatewya"
        }

        test("a correct refPart passes") {
            val (status, out) =
                validate(
                    """
                    sysml2Model(name = "R") {
                        val gw = partDef(name = "Gateway")
                        val ctl = partDef(name = "Controller") { refPart(name = "gw", typeId = gw.id) }
                        bdd(name = "D") {
                            include(ctl)
                            include(gw)
                        }
                    }
                    """.trimIndent(),
                )
            out shouldNotContain "SYSML2_REFPART_TYPE_UNKNOWN"
            status shouldBe 0
        }
    })
