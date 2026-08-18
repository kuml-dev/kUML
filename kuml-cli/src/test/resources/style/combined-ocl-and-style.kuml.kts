// style/combined-ocl-and-style.kuml.kts — deliberately carries BOTH a genuine
// OCL constraint violation (the class has no attributes, so the invariant
// fails) AND a source-style violation (constraint()'s name/body arguments
// passed positionally). Used to prove ValidateCommand/ValidateTool report
// both kinds of finding in one run, not just one or the other.
diagram(name = "Combined", type = DiagramType.CLASS) {
    classOf(name = "Empty") {
        constraint("hasAttr", "self.attributes->size() > 0")
    }
}
