package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/** Wave 2E-a - `kuml validate` warns about the same reqId with different text (code 218) and about showSubjectEdges outside REQ. */
class ValidateCommandReqIdTest :
    FunSpec({
        fun validate(script: String): String {
            val f = File.createTempFile("kuml-reqid-", ".kuml.kts")
            try {
                f.writeText(script)
                return KumlCli().test(listOf("validate", f.absolutePath)).output
            } finally {
                f.delete()
            }
        }

        test("218: the same reqId with different text is reported as a warning") {
            val out =
                validate(
                    """
                    sysml2Model(name = "R") {
                        val a = requirementDef(name = "A", reqId = "R-1", text = "The system shall start.")
                        val ext = externalRequirement(reqId = "R-1", source = "Doc 11", text = "The system shall stop.")
                        reqDiagram(name = "Req") {
                            include(a)
                            include(ext)
                        }
                    }
                    """.trimIndent(),
                )
            out shouldContain "REQUC218"
            out shouldContain "share reqId 'R-1'"
        }

        test("218: identical text and copy-connected pairs give no 218") {
            val out =
                validate(
                    """
                    sysml2Model(name = "R") {
                        val a = requirementDef(name = "A", reqId = "R-1", text = "The system shall start.")
                        val ext = externalRequirement(reqId = "R-1", source = "Doc 11", text = "The system  shall start.")
                        reqDiagram(name = "Req") {
                            include(a)
                            include(ext)
                        }
                    }
                    """.trimIndent(),
                )
            out shouldNotContain "REQUC218"
        }

        test("438: showSubjectEdges on a block definition diagram is a layout warning") {
            val out =
                validate(
                    """
                    sysml2Model(name = "B") {
                        val a = partDef(name = "A")
                        bdd(name = "D") {
                            include(a)
                            layout { showSubjectEdges = true }
                        }
                    }
                    """.trimIndent(),
                )
            out shouldContain "438"
        }
    })
