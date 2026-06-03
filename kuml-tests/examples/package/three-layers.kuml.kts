// three-layers.kuml.kts — UML 2.x package diagram (V1.1).
// Classic 3-layer architecture: web → service → repository.

packageDiagram(name = "Three-Layer Architecture") {
    val web = packageOf(name = "web")
    val service = packageOf(name = "service")
    val repository = packageOf(name = "repository")

    packageImport(client = web, supplier = service)
    packageImport(client = service, supplier = repository)
}
