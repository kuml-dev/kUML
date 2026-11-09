@file:Suppress("RequireNamedArguments")

package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.TaskType
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.note.Pdflatex
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Builds a real PDF from a process with all task types (skipped when TeX is not installed). */
class BpmnTaskTypePdfBuildTest :
    StringSpec({
        "all task types compile in a strict TeX run (skipped when TeX is not installed)".config(enabled = Pdflatex.enabled) {
            val tasks = TaskType.entries.mapIndexed { i, t -> BpmnTask(id = "t$i", name = "Task $i", taskType = t) }
            val p = BpmnProcess(id = "p1", name = "P", flowNodes = tasks)
            val tex =
                KumlLatexRenderer.toLatex(
                    model = BpmnModel(name = "M", processes = listOf(p), diagrams = listOf(ProcessDiagram(name = "D", processId = "p1"))),
                    options =
                        dev.kuml.io.latex
                            .LatexRenderOptions(standalone = true),
                )
            val dir = Files.createTempDirectory("kuml-bpmn-tasktype-").toFile()
            try {
                File(dir, "tt.tex").writeText(tex)
                val proc =
                    ProcessBuilder(
                        Pdflatex.requireExecutable().path,
                        "-no-shell-escape",
                        "-interaction=nonstopmode",
                        "-halt-on-error",
                        "tt.tex",
                    ).directory(dir)
                        .redirectErrorStream(true)
                        .start()
                proc.outputStream.close()
                val out = proc.inputStream.bufferedReader().readText()
                if (!proc.waitFor(300, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    error("pdflatex timed out")
                }
                check(proc.exitValue() == 0) { "pdflatex failed:\n${out.takeLast(2000)}" }
                out shouldNotContain "Undefined control sequence"
                out shouldNotContain "No shape named"
                File(dir, "tt.pdf").exists() shouldBe true
            } finally {
                dir.deleteRecursively()
            }
        }
    })
