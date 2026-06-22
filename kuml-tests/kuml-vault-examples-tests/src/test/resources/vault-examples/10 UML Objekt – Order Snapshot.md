---
title: UML Objektdiagramm – Order Snapshot
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - objektdiagramm
status: aktiv
---

# UML Objektdiagramm — Order Snapshot

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Minimal-Beispiel für ein **UML-Objektdiagramm** — eine *Snapshot-Sicht* auf konkrete Instanzen der `Customer`- und `Order`-Klassen aus [[01 UML Klasse – Order Domain]]. Zeigt **Slots** (konkrete Attributwerte) und einen **Link** zwischen `alice` und `order42`. Ideal für Testfixturen oder Beispiel-Konfigurationen, die ein Klassendiagramm dokumentieren soll.

## Diagramm

```kuml
// Klassifikatoren werden direkt als UmlClass konstruiert — `classOf` ist auf
// einen UmlModelScope (z. B. innerhalb von classDiagram { … }) beschränkt,
// den wir hier nicht öffnen.
val customer = UmlClass(
    id = "Customer",
    name = "Customer",
    attributes = listOf(
        UmlProperty(id = "Customer::id",   name = "id",   type = UmlTypeRef("UUID")),
        UmlProperty(id = "Customer::name", name = "name", type = UmlTypeRef("String")),
    ),
)

val order = UmlClass(
    id = "Order",
    name = "Order",
    attributes = listOf(
        UmlProperty(id = "Order::id",       name = "id",       type = UmlTypeRef("UUID")),
        UmlProperty(id = "Order::amount",   name = "amount",   type = UmlTypeRef("BigDecimal")),
        UmlProperty(id = "Order::customer", name = "customer", type = UmlTypeRef("Customer")),
    ),
)

objectDiagram(name = "Order #42 Snapshot") {
    val alice = instanceOf(classifier = customer, name = "alice") {
        slot(feature = "id",   value = literal(text = "c0ffee-001"))
        slot(feature = "name", value = literal(text = "Alice Müller"))
    }

    val ord = instanceOf(classifier = order, name = "order42") {
        slot(feature = "id",       value = literal(text = "o-42"))
        slot(feature = "amount",   value = literal(text = "19.95 EUR"))
        slot(feature = "customer", value = ref(instance = alice))
    }

    link(from = alice, to = ord, sourceRole = "buyer", targetRole = "purchase")
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `UmlClass(id = …, name = …, attributes = listOf(…))` außerhalb des Diagramms | Klassifikatoren werden direkt als Metamodell-Objekte konstruiert. `classOf` ist nicht verfügbar, weil ein `objectDiagram { … }` keinen `UmlModelScope` öffnet — Instanzen referenzieren die hier gebaute Klasse. |
| `UmlProperty(id = …, name = …, type = UmlTypeRef("…"))` | Attribute werden als typisierte Properties am Klassifikator vorab deklariert. Die Slot-Auflösung per Feature-Name greift dann auf die `id`. |
| `objectDiagram(name = …) { … }` | Top-Level: erzeugt ein Objektdiagramm (Snapshot). |
| `instanceOf(classifier = customer, name = "alice") { … }` | Erzeugt eine `UmlInstanceSpecification` der angegebenen Klasse. |
| `slot(feature = "name", value = literal(text = "Alice"))` | Belegt ein Attribut mit einem Literal-Wert. Die `definingFeatureId` wird automatisch über den Feature-Namen auf der Klassifikator-Klasse aufgelöst. |
| `slot(feature = "customer", value = ref(alice))` | Belegt ein Attribut mit einer **Instanz-Referenz** statt eines Literals. |
| `link(from = alice, to = ord, sourceRole = …, targetRole = …)` | Verbindet zwei Instanzen — das Snapshot-Äquivalent einer Assoziation. |

## Display-Optionen

```kotlin
objectDiagram(name = "…") {
    showClassifierType   = false   // unterdrückt :Customer-Suffix am Knoten
    showSlotCompartment  = true    // erzwingt Slot-Box auch wenn leer
    showNullSlots        = false   // null-belegte Slots ausblenden
    // … Instanzen + Links …
}
```

## Mögliche Erweiterungen

- **Anonyme Instanzen**: `instanceOf(classifier = customer)` ohne Name — bekommen eine generierte ID
- **Nullwerte**: `slot(feature = "shippedAt", value = nullValue)` für nicht-belegte Optionalfelder (`nullValue` ist ein `val`, kein function call)
- **Mehrere Snapshots**: pro Test-Szenario ein eigenes `objectDiagram` — z. B. *vor Bezahlung* / *nach Bezahlung*

## Verwandte Beispiele

- [[01 UML Klasse – Order Domain]] — Typebene zu diesem Snapshot
- [[19 UML Sequence – API Submit]] — gleiches Domänen-Setup als Verhaltensmodell
