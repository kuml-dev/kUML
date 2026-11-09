@file:Suppress("RequireNamedArguments")

package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.TaskType
import dev.kuml.io.latex.KumlLatexRenderer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private fun renderTask(
    id: String,
    type: TaskType,
    name: String = "Work",
): String {
    val p = BpmnProcess(id = "p1", name = "P", flowNodes = listOf(BpmnTask(id = id, name = name, taskType = type)))
    return KumlLatexRenderer.toLatex(
        model = BpmnModel(name = "M", processes = listOf(p), diagrams = listOf(ProcessDiagram(name = "D", processId = "p1"))),
    )
}

private val LETTERS =
    mapOf(
        TaskType.USER to "U",
        TaskType.SEND to "S",
        TaskType.RECEIVE to "R",
        TaskType.MANUAL to "M",
        TaskType.SCRIPT to "\\#",
        TaskType.BUSINESS_RULE to "B",
    )

private fun markers(tex: String) = tex.lines().filter { it.contains("\\node[kuml-bpmn-tasktype") }

class BpmnTaskTypeMarkerLatexTest :
    StringSpec({
        "each letter type has exactly one marker at the top-left corner of its own node" {
            for ((type, letter) in LETTERS) {
                val tex = renderTask(id = "t1", type = type)
                val m = markers(tex)
                m.size shouldBe 1
                m.single().trim() shouldBe
                    "\\node[kuml-bpmn-tasktype,anchor=north west] at ([xshift=4pt,yshift=-4pt]t1.north west) {$letter};"
                tex shouldContain "\\node[kuml-bpmn-task] (t1) at (0,0) {Work};"
            }
        }

        "the label no longer carries the type and no tiny text" {
            for (type in TaskType.entries) {
                val tex = renderTask(id = "t1", type = type)
                tex.lines().filter { it.contains("(t1)") }.forEach {
                    it shouldNotContain "\\tiny"
                    it shouldNotContain "~Work"
                }
            }
        }

        "NONE has no marker and no gear" {
            val tex = renderTask(id = "t1", type = TaskType.NONE)
            markers(tex).size shouldBe 0
            tex shouldNotContain "circle (2.2pt)"
        }

        "SERVICE draws a gear from plain TikZ, no letter node and no unicode" {
            val tex = renderTask(id = "t1", type = TaskType.SERVICE)
            markers(tex).size shouldBe 0
            Regex("""circle \(2\.2pt\)""").findAll(tex).count() shouldBe 1
            tex shouldContain "([xshift=7pt,yshift=-7pt]t1.north west)"
            tex shouldContain "\\foreach \\a in {0,45,...,315}"
            tex shouldNotContain "\u2699"
        }

        "a task id with TeX specials yields only the sanitised name" {
            val hostile = "t\\input{x} {1}"
            for (type in listOf(TaskType.USER, TaskType.SERVICE)) {
                val tex = renderTask(id = hostile, type = type)
                val safe = BpmnLatexRenderer.sanitizeTikzId(hostile)
                tex shouldNotContain "\\input{x}"
                tex shouldContain "$safe.north west"
            }
        }

        "the marker box and the first label line do not overlap" {
            // marker: 4pt..10pt below the top edge (6pt font). label: starts below the 10pt top padding of the task style.
            val markerBottom = 4f + 6f
            val labelTop = 10f
            (markerBottom <= labelTop) shouldBe true
            val tex = renderTask(id = "t1", type = TaskType.USER)
            tex shouldContain "inner xsep=4pt,inner ysep=10pt"
            tex shouldContain "kuml-bpmn-tasktype/.style={inner sep=0pt,font=\\sffamily\\bfseries\\fontsize{6}{6}\\selectfont}"
        }
    })
