diagram(name = "Valid", type = DiagramType.CLASS) {
    classOf("Order") {
        constraint("hasAttr", "self.attributes->size() > 0")
        attribute("id", "UUID")
    }
}
