package dev.kuml.cli

import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.KumlScriptHost
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class NoteStatsTest :
    FunSpec({
        fun stats(name: String): Map<NoteKind, Int> {
            val file = File("src/test/resources/notes/$name").absoluteFile
            val result = KumlScriptHost.eval(file = file)
            val extracted =
                DiagramExtractor.extractAny(
                    returnValue = (result as kotlin.script.experimental.api.ResultWithDiagnostics.Success).value.returnValue,
                    input = file,
                )
            return NoteChecks.statsOf(extracted)
        }

        test("statsOf counts declared SysML 2 comments by kind") {
            stats("sysml2-notes.kuml.kts") shouldBe mapOf(NoteKind.NOTE to 2)
        }

        test("statsOf counts declared ERM notes") {
            stats("erm-notes.kuml.kts") shouldBe mapOf(NoteKind.NOTE to 1)
        }

        test("NoteStats.plus adds totals, kinds and blocks") {
            val a = NoteChecks.NoteStats.ofKinds(mapOf(NoteKind.RISK to 1, NoteKind.ASSUMPTION to 2))
            val b = NoteChecks.NoteStats.ofKinds(mapOf(NoteKind.RISK to 2))
            val sum = a + b
            sum.total shouldBe 5
            sum.blocks shouldBe 2
            sum.byKind shouldBe mapOf(NoteKind.RISK to 3, NoteKind.ASSUMPTION to 2)
        }
    })
