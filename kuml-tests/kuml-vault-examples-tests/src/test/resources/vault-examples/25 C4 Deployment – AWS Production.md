---
title: C4 Deployment – AWS Production
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - c4
  - deployment
status: aktiv
---

# C4 Deployment — AWS Production

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **C4 Deployment Diagram** zeigt, *wie* die Container des Systems auf physischer bzw. Cloud-Infrastruktur verteilt sind. Hier: Eine AWS-Produktivumgebung mit einem EKS-Cluster, einer RDS-Datenbank und einem Edge-Load-Balancer. Im Vergleich zu [[14 UML Deployment – Cloud Stack]] arbeitet C4 grobgranularer auf Container-Ebene statt UML-Artefakten.

## Diagramm

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

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `deploymentNode(name = …) { … }` | Top-Level-Infrastruktur-Knoten; nur direkt im `c4Model`-Scope verfügbar. |
| `node(name = …) { … }` | Geschachtelter Kind-Knoten innerhalb eines `deploymentNode`-Blocks; kann beliebig tief verschachtelt werden. |
| `instances = 3` | Multiplicity am Knoten — wie viele Replicas. |
| `containerInstance(name = …, containerId = webApp.id)` | Container-zu-Node-Mapping — welcher `container()` läuft konkret auf diesem `node()`. |
| `location = "Internal"` | Freitext-Ortsangabe an `softwareSystem`/`person` (z. B. Internal/External-Deployment-Kontext). |
| `bidirectional = true` | Auf `relationship { … }` — rendert die Beziehung mit Pfeilen an beiden Enden. |
| `deploymentDiagram(name = …) { include(…) }` | Erzeugt das Deployment-Diagramm und filtert sichtbare Knoten. |

## C4-Diagrammhierarchie

| Ebene | Notiz |
|---|---|
| 1 — System Context | [[23 C4 Context – Internet Banking]] |
| 2 — Container | [[02 C4 Container – Internet Banking]] |
| 3 — Component | [[24 C4 Component – Web App Internals]] |
| 4 — Deployment ← *dieses Beispiel* / Dynamic | [[25 C4 Deployment – AWS Production]] · [[26 C4 Dynamic – Checkout Flow]] |

## Mögliche Erweiterungen

- **Mehrere Stages**: separate `deploymentDiagram` für DEV / STAGING / PROD
- **Multi-Region**: zwei `aws`-Knoten für eu-central-1 und us-east-1

## Verwandte Beispiele

- [[14 UML Deployment – Cloud Stack]] — UML-Pendant auf Artefakt-Ebene
- [[02 C4 Container – Internet Banking]] — *was* wird deployt (logische Bausteine)
