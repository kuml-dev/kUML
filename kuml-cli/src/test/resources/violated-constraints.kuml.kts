diagram(name = "Invalid", type = DiagramType.CLASS) {
    classOf("Empty") {
        constraint("hasAttr", "self.attributes->size() > 0")
    }
}
