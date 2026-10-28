package dev.kuml.cli.workspace

import dev.kuml.bpmn.model.BpmnElement
import dev.kuml.c4.model.C4Element
import dev.kuml.core.model.KumlNamespaceMember
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.workspace.pdf.DiagramFacts
import dev.kuml.workspace.pdf.ElementRow
import dev.kuml.workspace.pdf.ModelNote
import dev.kuml.workspace.pdf.TraceKind
import dev.kuml.workspace.pdf.TraceLink

/**
 * Derives the facts the PDF export prints next to a diagram (element table, requirement traceability, model
 * notes) from the model the render pipeline extracted. Public so Desktop and MCP reuse the exact CLI logic.
 *
 * Returns `null` for diagram types without an element table.
 */
public object DiagramFactsExtractor {
    private val OPEN_MARKER = Regex("""^\s*(TODO|FIXME|OFFEN|OPEN)\b""", RegexOption.IGNORE_CASE)
    private const val MAX_DETAIL = 120

    public fun from(extracted: ExtractedDiagram): DiagramFacts? =
        when (extracted) {
            is ExtractedDiagram.Uml -> uml(extracted)
            is ExtractedDiagram.Sysml2 -> sysml2(extracted)
            is ExtractedDiagram.C4 -> c4(extracted)
            is ExtractedDiagram.Bpmn -> bpmn(extracted)
            is ExtractedDiagram.Erm -> erm(extracted)
            is ExtractedDiagram.Blueprint -> null
        }

    private fun kindOf(e: Any): String =
        (e::class.simpleName ?: "Element")
            .removePrefix("Uml")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")

    private fun uml(x: ExtractedDiagram.Uml): DiagramFacts {
        val rows =
            x.diagram.elements.filterIsInstance<KumlNamespaceMember>().map {
                ElementRow(kind = kindOf(it), name = it.name, detail = null)
            }
        return DiagramFacts(elements = rows, traces = emptyList(), notes = emptyList())
    }

    private fun nameOf(
        model: Sysml2Model,
        id: String,
    ): String = (model.elementById(id) as? KumlNamespaceMember)?.name ?: id

    private fun sysml2(x: ExtractedDiagram.Sysml2): DiagramFacts {
        val model = x.model
        val diagram = x.diagram
        val rows =
            diagram.elementIds.mapNotNull { id ->
                (model.elementById(id) as? KumlNamespaceMember)?.let { ElementRow(kind = kindOf(it), name = it.name, detail = null) }
            }
        val traces = mutableListOf<TraceLink>()
        if (diagram is ReqDiagram) {
            diagram.satisfies.forEach {
                traces +=
                    TraceLink(
                        kind = TraceKind.SATISFY,
                        sourceName = nameOf(model = model, id = it.sourceId),
                        targetName = nameOf(model = model, id = it.requirementId),
                        diagramName = diagram.name,
                    )
            }
            diagram.verifies.forEach {
                traces +=
                    TraceLink(
                        kind = TraceKind.VERIFY,
                        sourceName = nameOf(model = model, id = it.sourceId),
                        targetName = nameOf(model = model, id = it.requirementId),
                        diagramName = diagram.name,
                    )
            }
            // derive: sourceRequirement is the original, targetRequirement derives from it
            diagram.derives.forEach {
                traces +=
                    TraceLink(
                        kind = TraceKind.DERIVE,
                        sourceName = nameOf(model = model, id = it.sourceRequirementId),
                        targetName = nameOf(model = model, id = it.targetRequirementId),
                        diagramName = diagram.name,
                    )
            }
        }
        val visible = model.notesVisibleIn(diagram = diagram, visibleNodeIds = diagram.elementIds.toSet())
        val notes =
            visible.comments.map { c ->
                ModelNote(
                    text = c.note.body.take(300),
                    anchors = model.commentLinks.filter { it.link.noteId == c.id }.map { nameOf(model = model, id = it.link.targetId) },
                    open = OPEN_MARKER.containsMatchIn(c.note.body),
                )
            }
        return DiagramFacts(elements = rows, traces = traces, notes = notes)
    }

    private fun c4(x: ExtractedDiagram.C4): DiagramFacts {
        val ids = x.diagram.elements.toSet()
        val rows =
            x.model.elements
                .filter { ids.isEmpty() || it.id in ids }
                .map { e: C4Element -> ElementRow(kind = kindOf(e), name = e.name, detail = e.description?.take(MAX_DETAIL)) }
        return DiagramFacts(elements = rows, traces = emptyList(), notes = emptyList())
    }

    private fun bpmn(x: ExtractedDiagram.Bpmn): DiagramFacts {
        val ids = x.diagram.elementIds.toSet()
        val rows =
            x.model.processes.flatMap { p ->
                p.flowNodes.filter { ids.isEmpty() || it.id in ids }.map { n: BpmnElement ->
                    ElementRow(kind = kindOf(n), name = n.name.orEmpty().ifEmpty { n.id }, detail = null)
                }
            }
        return DiagramFacts(elements = rows, traces = emptyList(), notes = emptyList())
    }

    private fun erm(x: ExtractedDiagram.Erm): DiagramFacts {
        val rows =
            x.model.entities.map {
                ElementRow(kind = "Entity", name = it.name ?: it.id, detail = "${it.attributes.size} attributes")
            }
        return DiagramFacts(elements = rows, traces = emptyList(), notes = emptyList())
    }
}
