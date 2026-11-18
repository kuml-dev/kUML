---
type: C4DeploymentDiagram
title: c4-deployment
---
# c4-deployment

```kuml
c4Model(name = "Internet Banking — Deployment") {
    // Referenz auf den Container vor dem softwareSystem-Block deklarieren,
    // damit containerInstance() im deploymentNode-Block darauf zugreifen kann.
    lateinit var webApp: C4Container

    val banking = softwareSystem(name = "Internet Banking") {
        location = "Internal"
        webApp = container(name = "Web Application") { technology = "Kotlin/Ktor" }
        container(name = "API Server") { technology = "Kotlin/Spring Boot" }
        container(name = "Database")   { technology = "PostgreSQL" }
    }

    val aws = deploymentNode(name = "AWS eu-central-1") {
        node(name = "EKS Cluster") {
            node(name = "web-pod") {
                instances = 3
                // Container-zu-Node-Mapping: dieser Pod führt eine Instanz von "Web Application" aus
                containerInstance(name = "Web Application", containerId = webApp.id)
            }
            node(name = "api-pod") {
                instances = 6
            }
        }
        node(name = "RDS Postgres") {
            instances = 1
        }
    }

    val edge = deploymentNode(name = "CloudFront Edge")

    // Edge und Web-Pod tauschen in beide Richtungen Health-Checks aus
    relationship(source = edge, target = aws) {
        technology = "HTTPS"
        bidirectional = true
    }

    deploymentDiagram(name = "AWS — Production") {
        include(aws, edge)
    }
}
```
