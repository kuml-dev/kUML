package dev.kuml.web.api

internal object Examples {
    data class ExampleMeta(
        val name: String,
        val title: String,
        val resource: String,
    )

    private val all: List<ExampleMeta> =
        listOf(
            ExampleMeta(name = "uml-class", title = "UML Class Diagram", resource = "/web/examples/uml-class.kuml.kts"),
            ExampleMeta(name = "c4-container", title = "C4 Container Diagram", resource = "/web/examples/c4-container.kuml.kts"),
            ExampleMeta(name = "sysml2-bdd", title = "SysML 2 Block Definition", resource = "/web/examples/sysml2-bdd.kuml.kts"),
            ExampleMeta(name = "erm-martin", title = "ERM (Crow's-Foot / Martin)", resource = "/web/examples/erm-martin.kuml.kts"),
        )

    fun list(): List<ExampleEntry> = all.map { ExampleEntry(name = it.name, title = it.title) }

    fun source(name: String): String? {
        val meta = all.find { it.name == name } ?: return null
        return Examples::class.java
            .getResourceAsStream(meta.resource)
            ?.bufferedReader()
            ?.readText()
    }
}
