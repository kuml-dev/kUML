@file:Suppress("unused")

import dev.kuml.c4.dsl.*
import dev.kuml.c4.model.*

c4Model("Internet Banking System") {
    val system =
        softwareSystem("Internet Banking") {
            container("Web Application") { technology = "Kotlin/Ktor" }
            container("API Server") { technology = "Kotlin/Spring Boot" }
            container("Database") { technology = "PostgreSQL" }
            container("Message Queue") { technology = "RabbitMQ" }
        }

    val containers =
        system.containers.mapNotNull { cId ->
            elements.filterIsInstance<C4Container>().find { it.id == cId }
        }

    relationship(containers[0], containers[1])
    relationship(containers[1], containers[2])
    relationship(containers[1], containers[3])

    val podWeb = deploymentNode("Pod - Web") { technology = "Kubernetes Pod" }
    val podApi = deploymentNode("Pod - API") { technology = "Kubernetes Pod" }
    val podDatabase = deploymentNode("Pod - Database") { technology = "Kubernetes Pod" }
    val podQueue = deploymentNode("Pod - Message Queue") { technology = "Kubernetes Pod" }

    val cluster =
        deploymentNode("Kubernetes Cluster") {
            technology = "Kubernetes 1.24"
            instances = 3
            children.add(podWeb)
            children.add(podApi)
            children.add(podDatabase)
            children.add(podQueue)
        }

    deploymentDiagram("Production Deployment") {
        include(cluster)
        title("Production Deployment - Kubernetes")
    }
}
