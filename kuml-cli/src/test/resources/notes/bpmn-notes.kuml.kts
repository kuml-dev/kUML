import dev.kuml.bpmn.dsl.bpmnModel

// Fixture: BPMN text annotation.
bpmnModel("NoteDemo") {
    process(id = "p", name = "Order") {
        val s = startEvent("Start")
        val t = task("Check")
        s flowsTo t
        textAnnotation("annotated", t, id = "ann1")
    }
    diagram(name = "Order", processId = "p")
}
