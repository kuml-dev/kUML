---
title: C4 Component – Web App Internals
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - c4
  - architektur
status: aktiv
---

# C4 Component — Web App Internals

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **C4 Component Diagram** ist die *dritte* Ebene des C4-Modells. Es zerlegt einen einzelnen Container in seine internen Komponenten. Hier: Die Web-App des Internet-Banking-Systems besteht aus einem `SecurityController`, einem `AccountHandler` und einem `TransactionMapper`.

## Diagramm

```kuml
c4Model(name = "Internet Banking — Components") {
    val customer = person(name = "Customer")
    val banking = softwareSystem(name = "Internet Banking") {
        container(name = "Web Application") {
            technology = "Kotlin/Ktor"
            component(name = "SecurityController") {
                technology = "Ktor Auth Plugin"
                description = "Login, Session-Verwaltung"
            }
            component(name = "AccountHandler") {
                technology = "Kotlin Coroutines"
                description = "Account-Übersicht, Transaktionen"
            }
            component(name = "TransactionMapper") {
                technology = "Kotlinx Serialization"
                description = "DTO ↔ Domain Mapping"
            }
        }
        container(name = "API Server") {
            technology = "Kotlin/Spring Boot"
        }
        container(name = "Database") {
            technology = "PostgreSQL"
        }
    }

    // Auflösen der Instanzen aus dem Modell-Scope
    val webApp = elements.filterIsInstance<C4Container>()
        .first { it.name == "Web Application" }
    val apiServer = elements.filterIsInstance<C4Container>()
        .first { it.name == "API Server" }
    val database = elements.filterIsInstance<C4Container>()
        .first { it.name == "Database" }
    val accountHandler = elements.filterIsInstance<C4Component>()
        .first { it.name == "AccountHandler" }
    val transactionMapper = elements.filterIsInstance<C4Component>()
        .first { it.name == "TransactionMapper" }

    relationship(source = accountHandler, target = apiServer) {
        description = "REST-Aufrufe"
    }
    relationship(source = transactionMapper, target = database) {
        description = "Datenbankzugriff"
    }

    componentDiagram(name = "Web App — Components") {
        container = webApp
        showExternalReferences = true
    }
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `softwareSystem(…) { container(…) { component(…) } }` | Schachtelung System → Container → Component. |
| `component(name = …) { technology = …; description = … }` | Logischer Baustein innerhalb eines Containers (Klasse, Service, Adapter). |
| `componentDiagram(name = …) { container = … }` | Erzeugt das Component-Diagramm; `container` referenziert den anzuzeigenden Container. |
| `elements.filterIsInstance<C4Container>().first { it.name == … }` | Auflösen einer Container-Instanz aus dem Modell-Scope (außerhalb von `softwareSystem`, weil `val`s innerhalb des Lambdas dort nicht sichtbar sind). |
| `showExternalReferences = true` | Zeigt verbundene Nachbar-Container (Datenbank, API-Server) als Kontext. |

## C4-Diagrammhierarchie

| Ebene | Notiz |
|---|---|
| 1 — System Context | [[23 C4 Context – Internet Banking]] |
| 2 — Container | [[02 C4 Container – Internet Banking]] |
| 3 — Component ← *dieses Beispiel* | [[24 C4 Component – Web App Internals]] |
| 4 — Deployment / Dynamic | [[25 C4 Deployment – AWS Production]] · [[26 C4 Dynamic – Checkout Flow]] |

## Mögliche Erweiterungen

- **Komponenten-Relationen**: `relationship(source = secCtrl, target = accountHandler)`
- **Klassen-Ebene (Code)**: bewusst nicht in C4 modelliert — siehe [[01 UML Klasse – Order Domain]]
- **Mehrere Container im Diagramm**: parallel zerlegen

## Verwandte Beispiele

- [[02 C4 Container – Internet Banking]] — eine Ebene gröber
- [[12 UML Component – Order Architecture]] — UML-Pendant mit Ports/Schnittstellen
