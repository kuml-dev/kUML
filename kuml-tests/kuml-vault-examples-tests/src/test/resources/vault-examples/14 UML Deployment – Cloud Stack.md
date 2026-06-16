---
title: UML Deployment – Cloud Stack
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - deployment
status: aktiv
---

# UML Deploymentdiagramm — Cloud Stack

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Deploymentdiagramm** zeigt, *auf welcher Hardware bzw. Laufzeitumgebung welche Artefakte ausgeführt werden*. Hier: Ein EKS-Cluster (Execution Environment) beherbergt einen Pod (Node) mit dem Container-Image `orderservice.jar`; daneben läuft eine PostgreSQL-Datenbank als Device.

## Diagramm

```kuml
deploymentDiagram(name = "Production Cloud Stack") {
    val cluster = executionEnvironment(name = "EKS Cluster") {
        val pod = node(name = "Pod: order-service") {
            artifact(name = "orderservice.jar")
            artifact(name = "config.yaml")
        }
    }

    val db = device(name = "PostgreSQL 16") {
        artifact(name = "orders.db")
    }

    val webApp = artifact(name = "webapp.war")
    val edge = node(name = "Edge Gateway")
    deploy(artifact = webApp, node = edge)

    communicationPath(end1 = cluster, end2 = db)
    communicationPath(end1 = edge, end2 = cluster)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `deploymentDiagram(name = …) { … }` | Top-Level: erzeugt ein Deployment-Diagramm. |
| `node(name = …) { … }` | Ausführungsknoten — typischerweise ein Server, eine VM, ein Pod. |
| `executionEnvironment(name = …) { … }` | Spezial-Node für Laufzeit-Umgebungen (Cluster, Container Runtime, App Server). Kann Nodes und Artefakte schachteln. |
| `device(name = …) { … }` | Spezial-Node für physische Geräte (DB-Server, IoT, Sensor). |
| `artifact(name = …)` | Deployable Einheit — JAR, WAR, Image, Config-Datei. |
| `deploy(artifact = …, node = …)` | `«deploy»`-Dependency: Artefakt läuft auf Node. |
| `communicationPath(end1 = …, end2 = …)` | `«communicationPath»`-Dependency zwischen zwei Nodes. |

## Mögliche Erweiterungen

- **Multiplicities an Nodes**: `node(name = "Web Pod") { /* ... */ }` plus Multiplicity-Annotation für `n` Replicas
- **Hardware-Stereotype**: zusätzlich `«device»`, `«executionEnvironment»` als Texte
- **Mehrere Stages**: zwei `deploymentDiagram` (DEV, PROD) im selben Modell

## Verwandte Beispiele

- [[12 UML Component – Order Architecture]] — *was* wird deployt (logische Bausteine)
- [[25 C4 Deployment – AWS Production]] — C4-Pendant — fokussiert auf Container-pro-Node
- [[02 C4 Container – Internet Banking]] — Architektur-Sicht ohne Infrastruktur
