---
title: UML Composite Structure – Order Internals
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - composite-structure
status: aktiv
---

# UML Composite Structure — Order Internals

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Composite-Structure-Diagramm** zeigt das *Innenleben* eines Klassifikators — seine Parts (typisierte Properties), Ports und die Connectors, die sie verdrahten. Hier: Der `OrderService` enthält intern ein `Validator`- und ein `Persistence`-Part, die über Ports an die äußere `IOrderApi` angebunden sind.

## Diagramm

```kuml
compositeStructureDiagram(name = "OrderService Internals") {
    val orderApi = interfaceOf(name = "IOrderApi") {
        operation(name = "placeOrder") { returns("OrderId") }
    }

    val dbApi = interfaceOf(name = "IPersistence") {
        operation(name = "save")
    }

    val service = component(name = "OrderService") {
        port(name = "api")
        provides(orderApi)
        port(name = "db")
        requires(dbApi)

        component(name = "Validator") {
            port(name = "in")
        }

        component(name = "Persistence") {
            port(name = "out")
            requires(dbApi)
        }
    }

    connect(end1 = service, port1 = "api",
            end2 = service, port2 = "api")
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `compositeStructureDiagram(name = …) { … }` | Top-Level: erzeugt ein Composite-Structure-Diagramm. |
| `component(name = "OrderService") { … }` | Der äußere Klassifikator, dessen Inneres gezeigt wird. |
| `port(name = …)` direkt im Component-Block | Boundary-Port des äußeren Klassifikators. |
| Geschachtelte `component(…)`-Blöcke | Parts (typisierte Properties) — hier `Validator` und `Persistence`. |
| `provides` / `requires` | Schnittstellenbindung an externen Ports. |
| `connect(end1 = …, port1 = …, end2 = …, port2 = …)` | Connector zwischen zwei Ports (auch innerhalb derselben Komponente erlaubt). |

## Display-Optionen

```kotlin
compositeStructureDiagram(name = "…") {
    showPortLabels = true   // Port-Namen sichtbar
    showRoleNames  = true   // Rollen-Namen an Connector-Enden
    // …
}
```

## Mögliche Erweiterungen

- **Collaboration**: alternativ `collaboration("OrderPlacement") { role("buyer", type = "Customer") }` für rollenbasiertes Modell ohne konkrete Klassifikatoren
- **Interface-Bindung über Connector**: explizite Lollipop-/Socket-Verdrahtung
- **Multiplicities an Parts**: `multiplicity(spec = "1..*")` für Mengenbeziehungen

## Verwandte Beispiele

- [[12 UML Component – Order Architecture]] — eine Ebene gröber: nur die Komponenten ohne Innenleben
- [[01 UML Klasse – Order Domain]] — Klassifikatorebene
- [[03 SysML 2 BDD – Hybrid Vehicle]] — SysML-2-Analogon (Block Definition + IBD)
- [[27 SysML 2 IBD – Hybrid Vehicle Wiring]] — Innenstruktur als SysML-2-IBD
