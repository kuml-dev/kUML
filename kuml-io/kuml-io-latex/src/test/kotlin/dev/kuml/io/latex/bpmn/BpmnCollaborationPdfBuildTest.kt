@file:Suppress("RequireNamedArguments")

package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.chain
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.end
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.model
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.start
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.task
import dev.kuml.io.latex.bpmn.BpmnCollaborationFixtures.toLatex
import dev.kuml.io.latex.note.Pdflatex
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private fun compile(
    name: String,
    tex: String,
): String {
    val dir = Files.createTempDirectory("kuml-bpmn-collab-").toFile()
    try {
        File(dir, "$name.tex").writeText(tex)
        val process =
            ProcessBuilder(Pdflatex.requireExecutable().path, "-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error", "$name.tex")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("pdflatex timed out for $name")
        }
        check(process.exitValue() == 0) { "pdflatex failed for $name:\n${output.takeLast(2000)}" }
        File(dir, "$name.pdf").exists() shouldBe true
        return output
    } finally {
        dir.deleteRecursively()
    }
}

/** Builds real PDFs from the BPMN collaboration output (skipped when TeX is not installed). */
class BpmnCollaborationPdfBuildTest :
    StringSpec({
        val tex = Pdflatex.enabled

        "collaboration with nested lanes, black-box pool and message flows compiles (skipped when TeX is not installed)".config(
            enabled = tex,
        ) {
            val log = compile(name = "collab-order", tex = toLatex(BpmnCollaborationFixtures.orderFlow(), standalone = true))
            log shouldNotContain "Undefined control sequence"
            log shouldNotContain "No shape named"
        }

        "collaboration with hostile texts and many lanes compiles (skipped when TeX is not installed)".config(enabled = tex) {
            val hostile = "\\input{/etc/passwd} \\write18{x} % } { # ^^5c ~ \$ & _"
            val p = chain(id = "p-1", name = hostile, nodes = listOf(start("a b", hostile), task("t\"1", hostile), end("e", hostile)))
            val lanes =
                (1..12).map {
                    BpmnLane(
                        id = "lane-$it",
                        name = hostile,
                        flowNodeRefs =
                            if (it ==
                                5
                            ) {
                                listOf("t\"1")
                            } else {
                                emptyList()
                            },
                    )
                }
            val m =
                model(
                    processes = listOf(p),
                    participants =
                        listOf(
                            BpmnParticipant(id = "pool one", name = hostile, processRef = "p-1", lanes = lanes),
                            BpmnParticipant(id = "pool two", name = hostile),
                        ),
                    messageFlows = listOf(MessageFlow(id = "m", name = hostile, sourceRef = "e", targetRef = "pool two")),
                    name = hostile,
                )
            val log = compile(name = "collab-hostile", tex = toLatex(m, standalone = true))
            log shouldNotContain "Undefined control sequence"
            log shouldNotContain "shell escape"
            log shouldNotContain "No shape named"
        }
    })
