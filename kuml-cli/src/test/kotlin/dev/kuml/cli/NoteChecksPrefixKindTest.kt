package dev.kuml.cli

import dev.kuml.core.model.note.KumlNoteCodes
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.model.note.NotePrefixKinds
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import kotlin.script.experimental.api.ResultWithDiagnostics

class NoteChecksPrefixKindTest :
    FunSpec({
        val table = NotePrefixKinds(mapOf("RISIKO" to NoteKind.RISK, "ANNAHME" to NoteKind.ASSUMPTION))

        fun extract(source: String): ExtractedDiagram {
            val dir = Files.createTempDirectory("kuml-w006").toFile()
            try {
                val file = File(dir, "t.kuml.kts").also { it.writeText(source) }
                val result = KumlScriptHost.eval(file = file)
                return DiagramExtractor.extractAny(
                    returnValue = (result as ResultWithDiagnostics.Success).value.returnValue,
                    input = file,
                )
            } finally {
                dir.deleteRecursively()
            }
        }

        fun w006(
            x: ExtractedDiagram,
            t: NotePrefixKinds = table,
        ) = NoteChecks.of(extracted = x, prefixKinds = t).filter { it.code == KumlNoteCodes.PREFIX_KIND_MISMATCH }

        fun sysml(vararg notes: String) =
            extract(
                """
                import dev.kuml.core.model.note.NoteKind
                import dev.kuml.sysml2.dsl.sysml2Model
                sysml2Model(name = "N") {
                    val v = partDef(name = "Vehicle")
                    bdd(name = "O") {
                        include(definition = v)
                        ${notes.joinToString("\n")}
                    }
                }
                """.trimIndent(),
            )

        test("SysML 2: mismatching kind yields one W006 warning with id and line") {
            val f = w006(sysml("""comment(text = "RISIKO: SECRETWORD", "Vehicle", id = "r1", kind = NoteKind.HINT)"""))
            f.size shouldBe 1
            f[0].isError shouldBe false
            f[0].subjectId shouldBe "r1"
            (f[0].sourceLine != null) shouldBe true
            f[0].message shouldContain "risiko"
            f[0].message shouldContain "RISK"
            f[0].message shouldContain "HINT"
            f[0].message shouldNotContain "SECRETWORD"
        }

        test("matching kind yields no finding, kind NOTE with prefix does") {
            w006(sysml("""comment(text = "RISIKO: x", "Vehicle", id = "r1", kind = NoteKind.RISK)""")) shouldBe emptyList()
            w006(sysml("""comment(text = "RISIKO: x", "Vehicle", id = "r1")""")).size shouldBe 1
        }

        test("three notes, one mismatch: exactly one finding with the right id") {
            val f =
                w006(
                    sysml(
                        """comment(text = "ANNAHME: a", "Vehicle", id = "a1", kind = NoteKind.ASSUMPTION)""",
                        """comment(text = "RISIKO: b", "Vehicle", id = "b1", kind = NoteKind.HINT)""",
                        """comment(text = "free text", "Vehicle", id = "c1")""",
                    ),
                )
            f.map { it.subjectId } shouldBe listOf("b1")
        }

        test("without a table the result is identical to the plain call") {
            val x = sysml("""comment(text = "RISIKO: x", "Vehicle", id = "r1", kind = NoteKind.HINT)""")
            NoteChecks.of(extracted = x) shouldBe NoteChecks.of(extracted = x, prefixKinds = NotePrefixKinds.EMPTY)
            NoteChecks.of(extracted = x).none { it.code == KumlNoteCodes.PREFIX_KIND_MISMATCH } shouldBe true
        }

        test("BPMN text annotation") {
            val x =
                extract(
                    """
                    import dev.kuml.bpmn.dsl.bpmnModel
                    import dev.kuml.core.model.note.NoteKind
                    bpmnModel("N") {
                        process(id = "p", name = "Order") {
                            val s = startEvent("Start")
                            val t = task("Check")
                            s flowsTo t
                            textAnnotation("RISIKO: x", t, id = "ann1", kind = NoteKind.HINT)
                        }
                        diagram(name = "Order", processId = "p")
                    }
                    """.trimIndent(),
                )
            w006(x).map { it.subjectId } shouldBe listOf("ann1")
        }

        test("ERM note") {
            val x =
                extract(
                    """
                    import dev.kuml.core.model.note.NoteKind
                    import dev.kuml.erm.dsl.ermModel
                    ermModel(name = "N") {
                        val c = entity(name = "Customer") { id() }
                        note("ANNAHME: x", c, id = "n1", kind = NoteKind.INFO)
                    }
                    """.trimIndent(),
                )
            w006(x).map { it.subjectId } shouldBe listOf("n1")
        }

        test("UML comment") {
            val x =
                extract(
                    """
                    import dev.kuml.core.model.note.NoteKind
                    import dev.kuml.uml.dsl.*
                    classDiagram(name = "N") {
                        val c = classOf(name = "Customer") { }
                        comment("RISIKO: x", c.id, id = "u1", kind = NoteKind.INFO)
                    }
                    """.trimIndent(),
                )
            w006(x).map { it.subjectId } shouldBe listOf("u1")
        }

        test("C4 notes are not checked") {
            val x =
                extract(
                    """
                    import dev.kuml.c4.dsl.c4Model
                    c4Model(name = "N") {
                        val user = person(name = "User")
                        val shop = softwareSystem(name = "Shop")
                        relationship(source = user, target = shop) { description = "uses" }
                        note("RISIKO: x", shop)
                        systemContextDiagram(name = "Context") { include(user, shop) }
                    }
                    """.trimIndent(),
                )
            w006(x) shouldBe emptyList()
        }
    })
