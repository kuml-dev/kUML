---
title: UML Komponentendiagramm – Order Architecture
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - komponentendiagramm
status: aktiv
---

# UML Komponentendiagramm — Order Architecture

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Komponentendiagramm** modelliert grobgranulare Bausteine eines Systems mit *Ports* und *Schnittstellen-Ablieferungen* (`provides`/`requires`). Hier: ein `OrderService` bietet eine `IOrderApi` über Port `api` an, ein `InvoiceService` konsumiert sie über Port `orderEvents` — verbunden durch einen Connector.

## Variante 1: Explizite Notation (mit Interface-Box)

Wird das Interface per `interfaceOf(...)` als eigener Knoten ins Diagramm gehoben, zeichnet der Renderer eine sichtbare Interface-Box plus zwei gestrichelte Beziehungspfeile:
- `provides(iface)` → `─▷` **Realization** (gestrichelt mit hohlem Dreieck) von der Komponente zur Box.
- `requires(iface)` → `─▷` **«use»-Dependency** (gestrichelt mit offenem Pfeil) von der Komponente zur Box.

```kuml
componentDiagram(name = "Order Architecture") {
    val orderApi = interfaceOf(name = "IOrderApi") {
        operation(name = "placeOrder") { returns("OrderId") }
        operation(name = "cancelOrder")
    }

    val orderService = component(name = "OrderService") {
        port(name = "api")
        provides(orderApi)
    }

    val invoiceService = component(name = "InvoiceService") {
        port(name = "orderEvents")
        requires(orderApi)
    }

    connect(end1 = orderService, port1 = "api",
            end2 = invoiceService, port2 = "orderEvents")
}
```

## Variante 2: Kurznotation (Lollipop / Socket)

Ohne `interfaceOf(...)` — also wenn das Interface nur per `providesById(...)` / `requiresById(...)` referenziert wird — entfällt die Box. Stattdessen hängt der Renderer ein kompaktes Symbol über die Komponentenkante:
- `providesById("X")` → **Lollipop** (Vollkreis am Stab-Stub), Label = ID.
- `requiresById("X")` → **Socket** (Halbkreis, Öffnung Richtung Komponente).

Geeignet, wenn die Operationen des Interfaces für das Diagramm nicht relevant sind oder wenn die Komponente nur ihre Vertrags-Signaturen zeigen soll, ohne dass die Schnittstelle als eigener Block dargestellt wird.

```kuml
componentDiagram(name = "Order Architecture (Lollipop)") {
    val orderService = component(name = "OrderService") {
        port(name = "api")
        providesById("IOrderApi")
    }

    val invoiceService = component(name = "InvoiceService") {
        port(name = "orderEvents")
        requiresById("IOrderApi")
    }

    connect(end1 = orderService, port1 = "api",
            end2 = invoiceService, port2 = "orderEvents")
}
```

> [!tip] Auto-Switch
> Der Renderer entscheidet selbstständig pro Interface-ID:
> - **ID ist als Knoten im Diagramm** → explizite Notation (Box + Realization/Dependency).
> - **ID ist kein Knoten** → Kurznotation (Lollipop/Socket).
>
> Beide Stile lassen sich in **einem einzigen Diagramm mischen** — z. B. zentrale Domain-Interfaces als Box, technische Querschnitts-Interfaces (Logging, Metrics) als Lollipop.

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `componentDiagram(name = …) { … }` | Top-Level: erzeugt ein Komponentendiagramm. |
| `interfaceOf(name = "IOrderApi") { … }` | UML-Interface als sichtbarer Knoten — fügt das Interface als Box ins Diagramm ein. |
| `component(name = …) { … }` | Komponente als Knoten. Kann Ports, Bereitstellungen, Anforderungen, **geschachtelte** Komponenten enthalten. |
| `port(name = "api")` | Definiert einen Port — ID wird automatisch qualifiziert (`OrderService::api`). |
| `provides(iface)` / `requires(iface)` | Markiert das Interface als bereitgestellt / benötigt. Mit Box (`interfaceOf`) → Realization-/Dependency-Pfeil. Ohne Box → Lollipop / Socket. |
| `providesById("X")` / `requiresById("X")` | Wie oben, nur per ID — typische Quelle der Kurznotation, weil kein Interface-Knoten erzeugt wird. |
| `connect(end1 = …, port1 = …, end2 = …, port2 = …)` | Connector zwischen zwei Ports. Endpunkte snappen auf die Port-Quadrate. |

## Mögliche Erweiterungen

- **Geschachtelte Komponenten**: `component("OrderService") { component("OrderRepository") }`
- **Dependencies ohne Interface**: `dependency(client = …, supplier = …)` für `«use»`-Abhängigkeiten
- **Stereotype**: `component(name = "OrderService", stereotypes = listOf("subsystem"))`

## Verwandte Beispiele

- [[11 UML Paket – Domain Modules]] — eine Ebene gröber: Pakete statt Komponenten
- [[13 UML Composite Structure – Order Internals]] — Innenleben einer Komponente
- [[02 C4 Container – Internet Banking]] — C4-Pendant auf Container-Ebene
- [[24 C4 Component – Web App Internals]] — C4-Pendant auf Component-Ebene
