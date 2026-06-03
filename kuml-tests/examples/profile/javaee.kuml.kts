// javaee.kuml.kts — UML 2.x profile diagram (V1.1).
// A tiny Java EE / Jakarta profile with three stereotypes extending the
// `Class` metaclass.

profileDiagram(name = "Java EE profile") {
    val entity = stereotype(name = "Entity", metaclasses = listOf("Class")) {
        tag(name = "tableName", type = "String")
    }
    val service = stereotype(name = "Service", metaclasses = listOf("Class"))
    val controller = stereotype(name = "Controller", metaclasses = listOf("Class")) {
        tag(name = "path", type = "String")
    }
    val mcClass = metaclass(name = "Class")
    extension(stereotype = entity, metaclass = mcClass)
    extension(stereotype = service, metaclass = mcClass)
    extension(stereotype = controller, metaclass = mcClass)
}
