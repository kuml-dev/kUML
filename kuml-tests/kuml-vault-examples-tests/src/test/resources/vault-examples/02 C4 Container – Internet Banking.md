---
title: C4 Container – Internet Banking
date: 2026-06-11
tags:
  - kUML
  - beispiel
  - c4
  - architektur
status: aktiv
---

# C4 Container — Internet Banking

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Minimal-Beispiel für ein **C4-Container-Diagramm**: ein Kunde nutzt ein Internet-Banking-System, das intern aus zwei Containern (Web-App + API-Server) besteht und mit einem externen E-Mail-Dienst kommuniziert. C4 ist die Architektur-Ebene **eine Stufe über** UML-Klassen.

## Diagramm

```kuml
c4Model(name = "Minimal Banking System") {
    val customer = person(name = "Customer")

    val bankSystem = softwareSystem(name = "Internet Banking") {
        container(name = "Web App") {
            technology = "Kotlin/Ktor"
        }

        container(name = "API Server") {
            technology = "Kotlin/Spring Boot"
        }
    }

    val emailService = softwareSystem(name = "Email Service") {
        external = true
    }

    relationship(source = customer, target = bankSystem) { technology = "HTTPS" }
    relationship(source = bankSystem, target = emailService) { technology = "SMTP" }

    containerDiagram(name = "Banking — Container View") {
        system = bankSystem
        showExternalSystems = true
    }
}
```

## C4-Konzepte in dieser Notiz

| C4-Element | Hier instanziert als |
|---|---|
| **Person** | `Customer` (menschlicher Nutzer) |
| **Software System** | `Internet Banking` (eigenes System), `Email Service` (extern via `external = true`) |
| **Container** | `Web App` (Kotlin/Ktor), `API Server` (Kotlin/Spring Boot) |
| **Relationship** | `Customer → Internet Banking` via HTTPS, `Internet Banking → Email Service` via SMTP |

## C4-Diagramm-Hierarchie (Kontext)

Im vollen C4-Modell gibt es vier Detailstufen — jede zoomt eine Ebene tiefer:

1. **System Context** — wer redet mit dem System? (Personen + externe Systeme)
2. **Container** ← *dieses Beispiel* — woraus besteht das System? (Apps, Datenbanken, Queues)
3. **Component** — woraus besteht ein Container? (Module, Services innerhalb einer App)
4. **Code** — typisch nicht modelliert, sondern direkt im IDE

## Mögliche Erweiterungen

- **Datenbank-Container**: `container(name = "Banking DB") { technology = "PostgreSQL" }`
- **System Context statt Container**: `systemContextDiagram(name = …) { system = bankSystem }` für höhere Abstraktionsebene
- **Components in einem Container**: innerhalb von `container { component(name = …) }` für tiefere Sicht
- **Stilisierung**: Schlüsselwort `tags = listOf("legacy", "critical")` für visuelles Hervorheben

## Verwandte Beispiele

- [[01 UML Klasse – Order Domain]] — Klassen-Ebene innerhalb eines Containers
- [[03 SysML 2 BDD – Hybrid Vehicle]] — Strukturelles Modell in SysML 2 (Engineering-Domäne statt Software)

## Verwandte Vault-Notizen

- [[03 Bereiche/kUML/ADR/ADR-0005 C4 als First-Class-Modellierungssprache]] — warum C4 in kUML First-Class ist
