---
title: C4 System Landscape – Enterprise Banking
date: 2026-06-11
tags:
  - kUML
  - beispiel
  - c4
  - landscape
  - architektur
status: aktiv
---

# C4 System Landscape — Enterprise Banking

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Höchste C4-Ebene: ein **System Landscape Diagram** zeigt die *gesamte* Systemlandschaft eines Unternehmens — alle Software-Systeme, alle Personen, alle externen Schnittstellen. Bonus: drei verschiedene **Views** auf dasselbe Modell (vollständig, intern-only, kunden-fokussiert).

## Diagramm

```kuml
import dev.kuml.c4.dsl.c4Model

c4Model(name = "Enterprise Banking Landscape") {
    // Persons / Roles
    val customer = person(name = "Customer") {
        description = "A customer using banking services"
    }

    val admin = person(name = "Administrator") {
        description = "System administrator managing banking infrastructure"
    }

    // Software Systems
    val mainBanking = softwareSystem(name = "Main Banking System") {
        description = "Handles customer accounts, transfers, and payments"
    }

    val creditCard = softwareSystem(name = "Credit Card System") {
        description = "Manages credit card operations and billing"
    }

    val loan = softwareSystem(name = "Loan Management System") {
        description = "Manages loan applications and disbursements"
    }

    val emailService = softwareSystem(name = "Email Service") {
        description = "Sends transactional and marketing emails"
        external = true
    }

    val smsService = softwareSystem(name = "SMS Notification Service") {
        description = "Sends SMS alerts and notifications"
        external = true
    }

    // Relationships
    relationship(source = customer, target = mainBanking) { description = "Uses" }
    relationship(source = customer, target = creditCard) { description = "Manages credit cards" }
    relationship(source = customer, target = loan) { description = "Applies for loans" }

    relationship(source = admin, target = mainBanking) { description = "Administers" }
    relationship(source = admin, target = creditCard) { description = "Administers" }

    relationship(source = mainBanking, target = emailService) { description = "Sends notifications via" }
    relationship(source = mainBanking, target = smsService) { description = "Sends alerts via" }
    relationship(source = creditCard, target = emailService) { description = "Sends billing statements via" }
    relationship(source = loan, target = emailService) { description = "Sends approval letters via" }

    // System Landscape Diagram — shows all systems and persons
    systemLandscapeDiagram(name = "Enterprise Banking Landscape") {
        description = "High-level overview of all systems and users in the banking enterprise"
    }
}
```

## Mehrere Views auf dasselbe Modell

Eine Stärke von C4 in kUML: dasselbe Modell, mehrere zugeschnittene Sichten. Das obige Skript zeigt nur die **Default-View**, lässt sich aber mit zusätzlichen Diagramm-Blöcken erweitern:

```kotlin
// Alternative view: nur interne Systeme
systemLandscapeDiagram(name = "Internal Systems Only") {
    description = "Shows only internal banking systems"
    exclude(emailService, smsService)
}

// Selektive View: Kunden-Sicht
systemLandscapeDiagram(name = "Customer Services") {
    description = "Services available to customers"
    include(customer, mainBanking, creditCard, loan)
}
```

| View | Was sie zeigt | Adressat |
|---|---|---|
| **Enterprise Banking Landscape** | Alles — der vollständige Überblick | Architekturboard, Geschäftsführung |
| **Internal Systems Only** | Nur eigene Systeme, ohne Drittanbieter | Interne Architektur-Reviews, Sicherheitsaudits |
| **Customer Services** | Nur was der Kunde nutzt | Produkt-Management, UX-Design |

## C4-Ebenen — wann welches Diagramm?

| Ebene | Diagramm | Wann |
|---|---|---|
| **1. System Landscape** ← *dieses Beispiel* | `systemLandscapeDiagram` | Du erklärst eine ganze Unternehmens-IT |
| **2. System Context** | `systemContextDiagram` | Du fokussierst *ein* System und seine Nachbarn |
| **3. Container** | `containerDiagram` | Du zeigst die internen Apps/DBs *eines* Systems |
| **4. Component** | `componentDiagram` | Du zeigst die Module *eines* Containers |

## DSL-Idiome

| Idiom | Effekt |
|---|---|
| `external = true` auf einem System | Markiert es visuell als Drittanbieter (grauer Kasten) |
| `include(...)` im Diagramm | Whitelist — nur diese Elemente sind sichtbar |
| `exclude(...)` im Diagramm | Blacklist — alles andere ist sichtbar |
| `description = "…"` | Tooltip / Beschreibungstext im Diagramm |
| `relationship(source, target) { description = …; technology = … }` | Beschriftete Kante zwischen zwei Elementen |

## Wo C4 in kUML besser ist als bei Structurizr

Structurizr ist die De-Facto-Referenz-Implementierung von C4 — aber:

| | Structurizr DSL | kUML |
|---|---|---|
| **Sprache** | Eigene DSL (textuell, nicht typsicher) | Kotlin (typsicher, IDE-Refactoring) |
| **Multi-Modell** | C4-only | UML, C4, **SysML 2** im selben Vault |
| **Render-Pipeline** | Cloud-Service oder Java | Pure Kotlin, plus PNG/SVG/LaTeX/PlantUML-Output |
| **Live-Behaviour** | nein | ja (siehe STM/ACT-Beispiele) |
| **Selbst-Hostbar** | ja (Open Source) | ja (Apache 2.0) |

Siehe auch [[03 Bereiche/kUML/ADR/ADR-0005 C4 als First-Class-Modellierungssprache]].

## Verwandte Beispiele

- [[02 C4 Container – Internet Banking]] — eine Ebene tiefer: Internas *eines* Systems aus diesem Landscape
- [[01 UML Klasse – Order Domain]] — *noch* eine Ebene tiefer: Klassen innerhalb eines Containers

## Verwandte Vault-Notizen

- [[03 Bereiche/kUML/ADR/ADR-0005 C4 als First-Class-Modellierungssprache]]
