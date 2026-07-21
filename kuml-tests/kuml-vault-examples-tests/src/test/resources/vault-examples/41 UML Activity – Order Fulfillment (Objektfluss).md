---
title: UML Activity – Order Fulfillment (Objektfluss)
date: 2026-07-21
tags:
  - kUML
  - beispiel
  - uml
  - activity
  - objectflow
status: aktiv
---

# UML Aktivitätsdiagramm — Order Fulfillment mit Objektfluss

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ergänzt [[17 UML Activity – Checkout Flow]] um das bisher nur in der Prosa erwähnte Element: **Objektfluss** (`objectFlow`). Während normale Kontrollfluss-Kanten (`edge(from, to)`) nur die Ausführungsreihenfolge zeigen, macht Objektfluss sichtbar, **welche Daten** zwischen Aktionen fließen — hier: eine Bestellung wird empfangen, daraus entstehen nacheinander die Datenobjekte `Order`, `Picked items` und `Package`, die jeweils als eigener Knoten zwischen den Aktionen sichtbar sind. Objektfluss-Kanten werden gestrichelt gerendert, um sie optisch vom reinen Kontrollfluss abzuheben.

> [!warning] Kommentar-Elemente bewusst nicht enthalten
> `comment()` (UML-Notiz) wurde für dieses Beispiel geprüft, ist aber für Aktivitätsdiagramme **nicht renderbar** — direkt am Renderer verifiziert, das Skript schlägt mit `comment()` in einem `activityDiagram { … }`-Block komplett fehl. Laut DSL-Quellcode (`CommentDsl.kt`) ist `comment()` bewusst nur für **Klassen-, Sequenz- und State-Machine-Diagramme** implementiert (dort existiert eine Renderer-/Layout-Bridge-Anbindung). Ein lauffähiges Beispiel für `comment()` gibt es bereits in [[01 UML Klasse – Order Domain]].

## Diagramm

```kuml
activityDiagram(name = "Order Fulfillment") {
    val start = initialNode()
    val receive = action(name = "Receive order")
    val order = objectNode(name = "Order")
    val pick = action(name = "Pick items")
    val pickedItems = objectNode(name = "Picked items")
    val pack = action(name = "Pack shipment")
    val pkg = objectNode(name = "Package")
    val ship = action(name = "Ship package")
    val done = finalNode()

    edge(from = start, to = receive)
    edge(from = receive, to = order, objectFlow = true)
    edge(from = order, to = pick, objectFlow = true)
    edge(from = pick, to = pickedItems, objectFlow = true)
    edge(from = pickedItems, to = pack, objectFlow = true)
    edge(from = pack, to = pkg, objectFlow = true)
    edge(from = pkg, to = ship, objectFlow = true)
    edge(from = ship, to = done)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `activityDiagram(name = …) { … }` | Top-Level: erzeugt ein Aktivitätsdiagramm. |
| `initialNode()` / `finalNode()` | Start- und Endknoten — hier reiner Kontrollfluss, keine Objektflüsse. |
| `action(name = …)` | Atomare Aktion. Produziert bzw. konsumiert hier jeweils ein Datenobjekt. |
| `objectNode(name = …)` | Datenobjekt, das zwischen Aktionen fließt (`UmlActivityNodeKind.OBJECT`). Wird wie ein einfacher Rechteck-Knoten gerendert. |
| `edge(from = …, to = …, objectFlow = true)` | Objektfluss-Kante — semantisch Datenfluss statt Kontrollfluss, optisch gestrichelt gerendert (`kuml-edge-dashed`). |
| `edge(from = …, to = …)` (ohne `objectFlow`) | Normale Kontrollfluss-Kante — hier für Start→Receive und Ship→Ende, wo keine Daten fließen. |

## Mögliche Erweiterungen

- **Guards auf Objektfluss-Kanten**: `edge(from = …, to = …, guard = "[in stock]", objectFlow = true)` — Bedingung, unter der ein Objekt weiterfließt.
- **Partitions (Swimlanes)**: `partition("Warehouse") { … }` um Aktionen nach Verantwortungsbereich zu gruppieren (z. B. Warehouse vs. Shipping).
- **Fork/Join parallel zum Objektfluss**: z. B. `Pack shipment` und eine parallele `Generate invoice`-Aktion, die beide von `Order` abzweigen.

## Verwandte Beispiele

- [[17 UML Activity – Checkout Flow]] — reiner Kontrollfluss mit Decision-Node, kein Objektfluss
- [[01 UML Klasse – Order Domain]] — funktionierendes `comment()`-Beispiel (Klassendiagramm)
- [[10 UML Objekt – Order Snapshot]] — Objekt-Diagramm (Instanz-Sicht), thematisch verwandt aber anderer Diagrammtyp als der Objektfluss-Knoten hier
- [[07 SysML 2 ACT – Order Processing]] — SysML-2-Pendant mit Swimlanes
