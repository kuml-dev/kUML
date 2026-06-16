---
title: UML Klassendiagramm – Order Domain
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - klassendiagramm
status: aktiv
---

# UML Klassendiagramm — Order Domain

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Klassendiagramm einer kleinen Bestelldomäne mit **vier Klassen** (`Customer`, `Order`, `OrderItem`, `Subscription`), einem **Interface** (`Payable`), einem **Enum** (`OrderStatus`), einer **1-zu-n-Assoziation**, einer **Komposition** und einer **Generalisierung** `Subscription` *erbt von* `Order`. Demonstriert das volle Vokabular der UML-Klassen-DSL: Attribute, Multiplizitäten, Rollen, Implementierung von Schnittstellen, Vererbung und Komposition.

## Diagramm

```kuml
classDiagram(name = "Order Domain") {
    val status = enumOf(name = "OrderStatus") {
        literal(name = "DRAFT")
        literal(name = "CONFIRMED")
        literal(name = "SHIPPED")
        literal(name = "CANCELLED")
    }

    val payable = interfaceOf(name = "Payable") {
        operation(name = "pay") { returns("Boolean") }
    }

    val customer = classOf(name = "Customer") {
        attribute(name = "id",    type = "UUID")
        attribute(name = "name",  type = "String")
        attribute(name = "email", type = "String")
    }

    val order = classOf(name = "Order") {
        attribute(name = "id",     type = "UUID")
        attribute(name = "status", type = status)
        attribute(name = "total",  type = "BigDecimal")
        implements(iface = payable)
    }

    val orderItem = classOf(name = "OrderItem") {
        attribute(name = "quantity",  type = "Int")
        attribute(name = "unitPrice", type = "BigDecimal")
    }

    val subscription = classOf(name = "Subscription") {
        attribute(name = "renewalDate", type = "LocalDate")
        attribute(name = "interval",    type = "Period")
    }

    // Generalisierung: Subscription erbt von Order
    generalization(specific = subscription, general = order)

    // Assoziation: Kunde besitzt 0..n Bestellungen
    association(source = customer, target = order) {
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*"); role = "orders" }
    }

    // Komposition: eine Bestellung besteht aus 1..n OrderItems
    association(source = order, target = orderItem) {
        aggregation = AggregationKind.COMPOSITE
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "1..*"); role = "items" }
    }
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `classDiagram(name = …) { … }` | Top-Level: erzeugt ein UML-Klassendiagramm. |
| `enumOf(name = …) { literal(name = …) }` | Aufzählungstyp mit Literalen. Wird per `type = status` an Attributen referenziert. |
| `interfaceOf(name = …) { operation(…) }` | Schnittstelle mit Operationen. |
| `classOf(name = …) { … }` | Klasse als Knoten. `val` für Referenzierung in Relationen. |
| `attribute(name = …, type = …)` | Attribut der Klasse. `type` kann ein String oder eine Enum-Referenz sein. |
| `implements(iface = payable)` | Realisierung einer Schnittstelle (gestrichelte Pfeil-Linie). |
| `generalization(specific = …, general = …)` | **Vererbung** — gefüllter Pfeil vom Kind zum Elternteil. |
| `association(source = …, target = …) { … }` | UML-Assoziation zwischen zwei Klassen. |
| `aggregation = AggregationKind.COMPOSITE` | Macht aus einer Assoziation eine **Komposition** (gefüllte Raute). `SHARED` für Aggregation, `NONE` für reine Assoziation. |
| `source { multiplicity(spec = "1") }` | Multiplizität am Quellenende. |
| `target { multiplicity(spec = "0..*"); role = "orders" }` | Multiplizität plus Rollen-Name am Zielende. |

## Beziehungen im Überblick

| Von → Nach | Kind | Bedeutung |
|---|---|---|
| `Subscription` → `Order` | Generalisierung | `Subscription` ist eine spezielle `Order`. |
| `Order` → `Payable` | Realisierung | `Order` implementiert die `pay()`-Operation. |
| `Customer` → `Order` | Assoziation 1 : 0..* | Ein Kunde hat beliebig viele Bestellungen. |
| `Order` → `OrderItem` | Komposition 1 : 1..* | Eine Bestellung *besitzt* mindestens ein Item; mit der Bestellung verschwinden auch die Items. |

## Alternative Schreibweise für die Generalisierung

Statt der top-level Funktion lässt sich Vererbung auch *innerhalb* des Klassenbodies deklarieren:

```kotlin
val subscription = classOf(name = "Subscription") {
    attribute(name = "renewalDate", type = "LocalDate")
    extends(general = order)   // ← in-class Generalisierungs-Shortcut
}
```

Beide Schreibweisen erzeugen dieselbe `UmlGeneralization`-Kante. Die top-level Variante ist explizit (alle Beziehungen am Ende des Diagramms), die `extends`-Variante ist näher am Code.

## Mögliche Erweiterungen

- **Mehrere Vererbungen**: `generalization(specific = electronicSubscription, general = subscription)` für tiefere Hierarchien
- **Abstrakte Klassen**: `classOf(name = "Order", isAbstract = true)` — Name wird *kursiv* gerendert
- **Operation mit Parametern**: `operation(name = "place") { parameter(name = "items", type = "List<OrderItem>"); returns("OrderId") }`
- **Stereotypen**: `classOf(name = "Customer", stereotypes = listOf("Entity"))` (siehe [[15 UML Profil – Java EE Profile]])
- **`showOperations = false`** auf dem Diagramm, um die Operationsfächer auszublenden — nützlich bei großen Klassen

## Verwandte Beispiele

- [[10 UML Objekt – Order Snapshot]] — konkrete Instanzen dieser Klassen als Snapshot
- [[18 UML State Machine – Order Lifecycle]] — Lebenszyklus einer `Order`
- [[19 UML Sequence – API Submit]] — Verhalten beim Anlegen einer `Order`
- [[02 C4 Container – Internet Banking]] — eine Ebene gröber als Architektur-Sicht
- [[03 SysML 2 BDD – Hybrid Vehicle]] — Strukturmodell in SysML 2 statt UML
