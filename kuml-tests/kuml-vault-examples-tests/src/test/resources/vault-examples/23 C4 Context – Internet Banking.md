---
title: C4 System Context – Internet Banking
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - c4
  - architektur
status: aktiv
---

# C4 System Context — Internet Banking

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **C4 System Context Diagram** ist die *oberste* Ebene des C4-Modells. Es zeigt das eigene System als Black-Box im Verbund mit allen Personen und externen Systemen, die mit ihm interagieren. Hier: Das `Internet Banking`-System aus Sicht eines Kunden, mit zwei externen Dienstleistern (E-Mail, Notifications).

## Diagramm

```kuml
c4Model(name = "Internet Banking — Context") {
    val customer = person(name = "Customer") {
        description = "Endnutzer mit Konto"
    }
    val support = person(name = "Support Agent") {
        description = "Interner Mitarbeiter"
    }

    val banking = softwareSystem(name = "Internet Banking") {
        description = "Webanwendung für Endkunden"
    }
    val email = softwareSystem(name = "Email Service") {
        external = true
    }
    val sms = softwareSystem(name = "SMS Gateway") {
        external = true
    }

    relationship(source = customer, target = banking) { technology = "HTTPS" }
    relationship(source = support,  target = banking) { technology = "HTTPS" }
    relationship(source = banking,  target = email)   { technology = "SMTP" }
    relationship(source = banking,  target = sms)     { technology = "HTTPS/JSON" }

    systemContextDiagram(name = "Banking — Context View") {
        include(customer, support, banking, email, sms)
    }
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `c4Model(name = …) { … }` | Top-Level: erzeugt ein C4-Modell. Elemente sind über alle Diagramme dieses Modells geteilt. |
| `person(name = …)` | Menschlicher Akteur. |
| `softwareSystem(name = …) { external = true }` | Eigene oder externe Systeme. `external = true` markiert Fremdsysteme grau. |
| `relationship(source = …, target = …) { technology = … }` | Relation auf Modell-Ebene — wird in allen Diagrammen verwendet, sofern beide Enden enthalten sind. |
| `systemContextDiagram(name = …) { include(…) }` | Erzeugt das Context-Diagramm. `include(…)` filtert, was sichtbar ist. |

## C4-Diagrammhierarchie

| Ebene | Notiz |
|---|---|
| 1 — System Context ← *dieses Beispiel* | [[23 C4 Context – Internet Banking]] |
| 2 — Container | [[02 C4 Container – Internet Banking]] |
| 3 — Component | [[24 C4 Component – Web App Internals]] |
| 4 — Deployment / Dynamic | [[25 C4 Deployment – AWS Production]] · [[26 C4 Dynamic – Checkout Flow]] |
| Enterprise — Landscape | [[09 C4 Landscape – Enterprise Banking]] |

## Mögliche Erweiterungen

- **Beschreibungen**: pro Person/System für mehr Kontext im Diagramm
- **Multiple Personen-Gruppen**: `tags = listOf("internal")` für visuelle Gruppierung
- **Mehrere Context-Views**: separat für „Customer-Sicht" und „Mitarbeiter-Sicht"

## Verwandte Beispiele

- [[02 C4 Container – Internet Banking]] — eine Ebene tiefer
- [[09 C4 Landscape – Enterprise Banking]] — gesamte Unternehmens-Landschaft
