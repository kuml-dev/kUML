// Minimal deployment-diagram fixture.
deploymentDiagram(name = "Tiny deploy") {
    val server = node(name = "Server")
    val db = device(name = "DB")
    communicationPath(end1 = server, end2 = db)
}
