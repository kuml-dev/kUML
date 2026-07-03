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
> Klassendiagramm einer kleinen Bestelldomäne mit **fünf Klassen** (`Customer`, `Order`, `OrderItem`, `Subscription`, `AbstractEntity`), einem **Interface** (`Payable`), einem **Enum** (`OrderStatus`), einer **1-zu-n-Assoziation**, einer **Komposition**, einer **gerichteten Abhängigkeit** und einer **Generalisierung** `Order` *erbt von* `AbstractEntity` bzw. `Subscription` *erbt von* `Order`. Demonstriert das volle Vokabular der UML-Klassen-DSL: Attribute (inkl. Sichtbarkeit, `static`, `readOnly`, Default-Werte), Operationen mit Parametern, OCL-Constraints, Multiplizitäten, Rollen, nicht-navigierbare Assoziationsenden, Implementierung von Schnittstellen, Vererbung, Komposition und Abhängigkeit.

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
        operation(name = "pay") { returns(typeName = "Boolean") }
    }

    // Abstrakte Basisklasse — Name wird kursiv gerendert
    val abstractEntity = classOf(name = "AbstractEntity") {
        isAbstract = true
        attribute(name = "id", type = "UUID", visibility = Visibility.PROTECTED, isReadOnly = true)
    }

    val customer = classOf(name = "Customer") {
        attribute(name = "id",    type = "UUID")
        attribute(name = "name",  type = "String")
        attribute(name = "email", type = "String")
    }

    val order = classOf(name = "Order") {
        attribute(name = "id",     type = "UUID")
        attribute(name = "status", type = status, defaultValue = "DRAFT")
        attribute(name = "total",  type = "BigDecimal", visibility = Visibility.PRIVATE)
        attribute(name = "taxRate", type = "BigDecimal", isStatic = true, defaultValue = "0.19")
        implements(iface = payable)
        extends(general = abstractEntity)

        operation(name = "place") {
            visibility = Visibility.PUBLIC
            parameter(name = "items", type = "List<OrderItem>")
            returns(typeName = "OrderId")
        }
        operation(name = "confirm") { returns(typeName = "Boolean") }

        // OCL-Invariante — wird per `kuml validate` geprüft
        constraint(name = "PositiveTotal", body = "self.total >= 0")
    }

    val orderItem = classOf(name = "OrderItem") {
        attribute(name = "quantity",  type = "Int")
        attribute(name = "unitPrice", type = "BigDecimal")
    }

    val subscription = classOf(name = "Subscription") {
        attribute(name = "renewalDate", type = "LocalDate")
        attribute(name = "interval",    type = "Period")
    }

    // Generalisierung: Subscription erbt von Order (top-level Schreibweise)
    generalization(specific = subscription, general = order)

    // Abhängigkeit: Order nutzt eine Notification-Klasse, ohne sie zu besitzen
    val notification = classOf(name = "NotificationService")
    dependency(client = order, supplier = notification, name = "notifies")

    // Assoziation: Kunde besitzt 0..n Bestellungen; Order kennt seinen Customer nicht (nicht navigierbar)
    association(source = customer, target = order) {
        source { multiplicity(spec = "1"); navigable = false }
        target { multiplicity(spec = "0..*"); role = "orders" }
    }

    // Komposition: eine Bestellung besteht aus 1..n OrderItems
    association(source = order, target = orderItem) {
        aggregation = AggregationKind.COMPOSITE
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "1..*"); role = "items" }
    }

    // UML-Notiz (Comment): Freitext-Kasten mit gefalteter Ecke, per gestrichelter
    // Linie an ein Element angehängt — hier an Order.
    comment(
        text = "Encapsulates the full order lifecycle from placement to fulfillment.",
        firstAnchor = order,
    )
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `classDiagram(name = …) { … }` | Top-Level: erzeugt ein UML-Klassendiagramm. |
| `enumOf(name = …) { literal(name = …) }` | Aufzählungstyp mit Literalen. Wird per `type = status` an Attributen referenziert. |
| `interfaceOf(name = …) { operation(…) }` | Schnittstelle mit Operationen. |
| `classOf(name = …) { isAbstract = true; … }` | Klasse als Knoten. `isAbstract = true` im Body rendert den Namen kursiv. `val` für Referenzierung in Relationen. |
| `attribute(name = …, type = …, visibility = …, isStatic = …, isReadOnly = …, defaultValue = …)` | Attribut der Klasse. `type` kann ein String oder eine Enum-Referenz sein. |
| `operation(name = …) { … }` | Operation mit `visibility`, `returnType`/`returns(typeName = …)`, `parameter(name = …, type = …)`. |
| `constraint(name = …, body = …)` | OCL-Invariante, ausgewertet von `kuml validate`. |
| `implements(iface = payable)` | Realisierung einer Schnittstelle (gestrichelte Pfeil-Linie). |
| `extends(general = …)` | In-Class-Kurzform für Generalisierung (siehe unten). |
| `generalization(specific = …, general = …)` | **Vererbung** — gefüllter Pfeil vom Kind zum Elternteil (Top-Level-Schreibweise). |
| `dependency(client = …, supplier = …, name = …)` | **Abhängigkeit** — gestrichelter offener Pfeil, Client nutzt Supplier ohne strukturelle Bindung. |
| `association(source = …, target = …) { … }` | UML-Assoziation zwischen zwei Klassen. |
| `aggregation = AggregationKind.COMPOSITE` | Macht aus einer Assoziation eine **Komposition** (gefüllte Raute). `SHARED` für Aggregation, `NONE` für reine Assoziation. |
| `source { multiplicity(spec = "1"); navigable = false }` | Multiplizität am Quellenende; `navigable = false` blendet den Navigationspfeil an diesem Ende aus. |
| `target { multiplicity(spec = "0..*"); role = "orders" }` | Multiplizität plus Rollen-Name am Zielende. |
| `comment(text = …, firstAnchor = …)` | **Notiz** (UML Comment/Note) — Freitext-Kasten mit gefalteter oberer rechter Ecke, per gestrichelter Linie an ein oder mehrere Elemente angehängt. Ohne Anker eine frei stehende Notiz. Aktuell nur für Klassen-, Sequenz- und Zustandsdiagramme verfügbar (v0.23.1). |

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
- **Stereotypen**: `classOf(name = "Customer", stereotypes = listOf("Entity"))` (siehe [[15 UML Profil – Java EE Profile]])
- **`showOperations = false`** auf dem Diagramm, um die Operationsfächer auszublenden — nützlich bei großen Klassen

## Verwandte Beispiele

- [[10 UML Objekt – Order Snapshot]] — konkrete Instanzen dieser Klassen als Snapshot
- [[18 UML State Machine – Order Lifecycle]] — Lebenszyklus einer `Order`
- [[19 UML Sequence – API Submit]] — Verhalten beim Anlegen einer `Order`
- [[02 C4 Container – Internet Banking]] — eine Ebene gröber als Architektur-Sicht
- [[03 SysML 2 BDD – Hybrid Vehicle]] — Strukturmodell in SysML 2 statt UML
