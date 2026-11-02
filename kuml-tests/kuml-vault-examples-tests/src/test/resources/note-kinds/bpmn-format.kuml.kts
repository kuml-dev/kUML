import dev.kuml.bpmn.dsl.*
import dev.kuml.bpmn.model.*

bpmnModel(name = "Formatted notes") {
    process(id = "p", name = "Order") {
        val start = startEvent(name = "Start")
        val check = task(name = "Check order")
        val ship = task(name = "Ship order")
        val done = endEvent(name = "Done")
        sequenceFlow(from = start, to = check)
        sequenceFlow(from = check, to = ship)
        sequenceFlow(from = ship, to = done)
        textAnnotation(text = "A plain control note.", start, kind = NoteKind.NOTE)
        textAnnotation(text = "**Hint:** call `validate()` first.\n- *stock*\n- credit", check, kind = NoteKind.HINT)
        textAnnotation(text = "Warning: see [the policy](https://example.org/policy) and [x](javascript:alert(1)).", ship, kind = NoteKind.WARNING)
    }
    diagram(name = "Formatted notes", processId = "p")
}
