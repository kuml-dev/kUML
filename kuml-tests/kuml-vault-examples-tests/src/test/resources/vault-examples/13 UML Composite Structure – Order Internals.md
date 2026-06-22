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
> Ein **UML-Composite-Structure-Diagramm** zeigt das *Innenleben* eines Klassifikators — seine Parts (typisierte Properties), Ports und die Connectors, die sie verdrahten. Hier: Der `OrderService` enthält intern ein `Validator`- und ein `Persistence`-Part; Delegation-Connectoren verkabeln die Boundary-Ports des äußeren Klassifikators mit den Port-Eingängen der inneren Parts.

## Diagramm

```kuml
compositeStructureDiagram(name = "OrderService Internals") {
    val orderApi = interfaceOf(name = "IOrderApi") {
        operation(name = "placeOrder") { returns(typeName = "OrderId") }
    }

    val dbApi = interfaceOf(name = "IPersistence") {
        operation(name = "save")
    }

    // Referenzen auf verschachtelte Parts vor dem Parent-Block deklarieren,
    // damit connect() auf Diagramm-Ebene darauf zugreifen kann.
    lateinit var validator: UmlComponent
    lateinit var persistence: UmlComponent

    val service = component(name = "OrderService") {
        port(name = "api")
        provides(iface = orderApi)
        port(name = "db")
        requires(iface = dbApi)

        validator = component(name = "Validator") {
            port(name = "in")    // empfängt eingehende Anfragen
            port(name = "out")   // gibt validierte Daten weiter
        }

        persistence = component(name = "Persistence") {
            port(name = "in")    // empfängt von Validator
            port(name = "db")    // Datenbankzugriff via Boundary-Port
            requires(iface = dbApi)
        }
    }

    // Delegation-Connector: Boundary-Port → innerer Part-Port
    connect(end1 = service,    port1 = "api", end2 = validator,   port2 = "in")
    // Assembly-Connector: Part-Port → Part-Port
    connect(end1 = validator,  port1 = "out", end2 = persistence, port2 = "in")
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `compositeStructureDiagram(name = …) { … }` | Top-Level: erzeugt ein Composite-Structure-Diagramm. |
| `component(name = "OrderService") { … }` | Der äußere Klassifikator, dessen Inneres gezeigt wird. |
| `port(name = …)` direkt im Component-Block | Boundary-Port des äußeren Klassifikators. |
| Geschachtelte `component(…)`-Blöcke | Parts (typisierte Properties) — hier `Validator` und `Persistence`, gerendert als Boxen *im Inneren* von `OrderService`. Die Blöcke geben `UmlComponent` zurück. |
| `lateinit var validator: UmlComponent` | Hoisting der Part-Referenz vor den Parent-Block, damit `connect()` auf Diagramm-Ebene darauf zugreift. |
| `provides` / `requires` | Schnittstellenbindung an externen Ports. |
| `connect(end1 = service, port1 = "api", end2 = validator, port2 = "in")` | **Delegation-Connector**: Boundary-Port des äußeren Klassifikators → Port eines inneren Parts. Wird als SVG-Linie gezeichnet, ohne ELK-Routing. |
| `connect(end1 = validator, port1 = "out", end2 = persistence, port2 = "in")` | **Assembly-Connector**: Port eines inneren Parts → Port eines anderen inneren Parts. Gleiche Syntax wie Delegation-Connector. |

## Display-Optionen

```kotlin
compositeStructureDiagram(name = "…") {
    showPortLabels = true   // Port-Namen sichtbar
    showRoleNames  = true   // Rollen-Namen an Connector-Enden
    // …
}
```

## Technische Details: Connector-Routing

Interne Connectors werden **ohne ELK** gezeichnet. Der SVG-Renderer berechnet die Endpunkte mit derselben Mathe wie die Port-Platzierung:

- **Boundary-Port** (Index *i* von *n*, linke Seite): Mittelpunkt = `(0, h*(i+1)/(n+1))`
- **Boundary-Port** (rechte Seite): Mittelpunkt = `(w, h*(i+1)/(n+1))`
- **Part-Port**: Position des Part-Rechtecks + Port-Offset innerhalb des Parts

Die Part-Positionen werden aus `chromeHeight()` + `compositeHeight()` abgeleitet — denselben Funktionen, die der `UmlContentSizeProvider` für die Layout-Größen verwendet.

> [!note] Aktuell unterstützte Tiefe
> Connectors funktionieren für direkte Parts (1 Ebene) und Grandchild-Components (2 Ebenen). Ports an Komponenten tiefer als `NESTED_MAX_DEPTH = 64` werden mit einem `IllegalStateException` abgefangen.

## Mögliche Erweiterungen

- **Collaboration**: alternativ `collaboration("OrderPlacement") { role("buyer", type = "Customer") }` für rollenbasiertes Modell ohne konkrete Klassifikatoren
- **Interface-Bindung über Connector**: explizite Lollipop-/Socket-Verdrahtung
- **Multiplicities an Parts**: `multiplicity(spec = "1..*")` für Mengenbeziehungen
- **Connector-Labels**: `connect(…, name = "delegates")` um Connectoren zu beschriften

## Verwandte Beispiele

- [[12 UML Component – Order Architecture]] — eine Ebene gröber: nur die Komponenten ohne Innenleben
- [[01 UML Klasse – Order Domain]] — Klassifikatorebene
- [[03 SysML 2 BDD – Hybrid Vehicle]] — SysML-2-Analogon (Block Definition + IBD)
- [[27 SysML 2 IBD – Hybrid Vehicle Wiring]] — Innenstruktur als SysML-2-IBD
