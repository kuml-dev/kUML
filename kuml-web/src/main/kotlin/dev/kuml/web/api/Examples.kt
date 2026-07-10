package dev.kuml.web.api

internal object Examples {
    data class ExampleMeta(
        val name: String,
        val title: String,
        val resource: String,
    )

    private val all: List<ExampleMeta> =
        listOf(
            ExampleMeta("uml-class", "UML Class Diagram", "/web/examples/uml-class.kuml.kts"),
            ExampleMeta("c4-container", "C4 Container Diagram", "/web/examples/c4-container.kuml.kts"),
            ExampleMeta("sysml2-bdd", "SysML 2 Block Definition", "/web/examples/sysml2-bdd.kuml.kts"),
            ExampleMeta("erm-martin", "ERM (Crow's-Foot / Martin)", "/web/examples/erm-martin.kuml.kts"),
        )

    fun list(): List<ExampleEntry> = all.map { ExampleEntry(it.name, it.title) }

    fun source(name: String): String? {
        val meta = all.find { it.name == name } ?: return null
        return Examples::class.java
            .getResourceAsStream(meta.resource)
            ?.bufferedReader()
            ?.readText()
    }
}
