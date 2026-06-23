// Deployment-diagram fixture: nested nodes + artifacts (regression for
// "children/artifacts not rendered" bug).
deploymentDiagram(name = "Cloud Stack Nested") {
    val cluster = executionEnvironment(name = "EKS Cluster") {
        val pod = node(name = "Pod: order-service") {
            artifact(name = "orderservice.jar")
            artifact(name = "config.yaml")
        }
    }

    val db = device(name = "PostgreSQL 16") {
        artifact(name = "orders.db")
    }

    communicationPath(end1 = cluster, end2 = db)
}
