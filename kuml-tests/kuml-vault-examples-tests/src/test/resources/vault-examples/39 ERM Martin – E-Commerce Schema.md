---
title: ERM Martin – E-Commerce Schema
date: 2026-07-09
tags:
  - kUML
  - beispiel
  - erm
  - martin
  - datenbank
  - e-commerce
status: aktiv
---

# ERM Martin — E-Commerce Schema

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> **Neutrales** relationales Beispielschema für einen kleinen Online-Shop — bewusst **ohne PZB-Bezug**, damit es 1:1 als Worked-Example auf der kuml.dev-Webseite wiederverwendet werden kann (siehe [[03 Bereiche/kUML/Bewertung kUML vs PlantUML vs Mermaid (DB-Modellierung)#Offene Punkte vor Webseiten-Übernahme|offener Punkt „neutrales Schema"]]). Gerendert wird in **Martin-Notation** (Crow's Foot) — der gebräuchlichsten der vier von kUML unterstützten ERM-Notationen (Bachman/Chen/IDEF1X sind seit V3.4.3–V3.4.5 ebenso produktionsreif, siehe „Mögliche Erweiterungen" für dasselbe Modell in den anderen Notationen). Das Schema deckt genau die Stolperstellen ab, die reale relationale Modelle typischerweise mitbringen: **mehrere Fremdschlüssel auf dieselbe Tabelle** (`Order` → `Address` zweimal, `Review` → drei verschiedene Ziele), eine **Self-Reference** (`Category.parent_id`), eine **identifizierende Beziehung / schwache Entität** (`OrderItem` als Kompositionskind von `Order`) sowie First-Class-**Views**, **Indizes** und **Check-Constraints**.

## Diagramm

```kuml
ermModel("E-Commerce Schema") {
    val customer =
        entity("Customer") {
            id()
            attribute(name = "email", type = ErmDataType.Varchar(255), unique = true, nullable = false)
            attribute(name = "name", type = ErmDataType.Varchar(255))
            attribute(name = "created_at", type = ErmDataType.Timestamp(), default = "now()")
        }

    val address =
        entity("Address") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
            attribute(name = "street", type = ErmDataType.Varchar(255))
            attribute(name = "city", type = ErmDataType.Varchar(120))
            attribute(name = "zip", type = ErmDataType.Varchar(20))
        }

    val category =
        entity("Category") {
            id()
            attribute(name = "name", type = ErmDataType.Varchar(120))
            // Self-reference: der eigene id-String ist im Entity-Block noch nicht
            // verfügbar, daher hier nur ein normales Attribut — die Kante kommt
            // unten als separates relationship(from = category, to = category, …).
            attribute(name = "parent_id", type = ErmDataType.Uuid, nullable = true)
        }

    val product =
        entity("Product") {
            id()
            foreignKey(name = "category_id", references = category, nullable = false)
            attribute(name = "sku", type = ErmDataType.Varchar(64), unique = true)
            attribute(name = "name", type = ErmDataType.Varchar(255))
            attribute(name = "price", type = ErmDataType.Decimal(10, 2), nullable = false)
            check(expression = "price >= 0", name = "positive_price")
            index("sku", unique = true, name = "idx_product_sku")
        }

    val order =
        entity("Order") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
            // Zwei Fremdschlüssel auf dieselbe Zieltabelle (Address) — Rechnungs-
            // und Lieferadresse. Unten als zwei relationship(...)-Aufrufe mit
            // unterschiedlichem targetRole abgebildet.
            foreignKey(name = "billing_address_id", references = address, nullable = false)
            foreignKey(name = "shipping_address_id", references = address, nullable = false)
            attribute(name = "status", type = ErmDataType.Varchar(32), default = "'pending'")
            attribute(name = "placed_at", type = ErmDataType.Timestamp(), default = "now()")
            index("status", name = "idx_order_status")
        }

    // Schwache Entität / identifizierende Beziehung: OrderItem existiert nur im
    // Kontext einer Order (Komposition, ON DELETE CASCADE).
    val orderItem =
        entity("OrderItem", weak = true) {
            foreignKey(name = "order_id", references = order, onDelete = ReferentialAction.CASCADE, nullable = false)
            foreignKey(name = "product_id", references = product, nullable = false)
            attribute(name = "quantity", type = ErmDataType.Integer(), nullable = false)
            attribute(name = "unit_price", type = ErmDataType.Decimal(10, 2), nullable = false)
        }

    val payment =
        entity("Payment") {
            id()
            foreignKey(name = "order_id", references = order, nullable = false)
            attribute(name = "amount", type = ErmDataType.Decimal(10, 2), nullable = false)
            attribute(name = "method", type = ErmDataType.Varchar(32))
            attribute(name = "paid_at", type = ErmDataType.Timestamp())
        }

    // Drei Fremdschlüssel auf drei verschiedene Zieltabellen aus einer einzigen Entität.
    val review =
        entity("Review") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
            foreignKey(name = "product_id", references = product, nullable = false)
            foreignKey(name = "order_id", references = order, nullable = false)
            attribute(name = "rating", type = ErmDataType.Integer(bits = 16), nullable = false)
            attribute(name = "comment", type = ErmDataType.Text)
            check(expression = "rating BETWEEN 1 AND 5", name = "rating_range")
        }

    // Jede oben deklarierte Fremdschlüsselspalte bekommt hier ihre eigene,
    // gerenderte relationship(...)-Kante (die ERM-Layout-Bridge zeichnet
    // ausschließlich model.relationships, nicht automatisch aus foreignKey()).
    relationship(from = customer, to = address, name = "has", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(
        from = category,
        to = category,
        name = "subcategory of",
        sourceRole = "parent",
        targetRole = "child",
        sourceCardinality = Cardinality.ZERO_ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(from = category, to = product, name = "groups", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = customer, to = order, name = "places", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(
        from = address,
        to = order,
        name = "bills",
        targetRole = "billing",
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(
        from = address,
        to = order,
        name = "ships",
        targetRole = "shipping",
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ZERO_MANY,
    )
    relationship(
        from = order,
        to = orderItem,
        name = "contains",
        kind = RelationshipKind.IDENTIFYING,
        sourceCardinality = Cardinality.ONE,
        targetCardinality = Cardinality.ONE_MANY,
    )
    relationship(from = product, to = orderItem, name = "ordered as", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = order, to = payment, name = "paid by", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = customer, to = review, name = "writes", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = product, to = review, name = "reviewed in", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    relationship(from = order, to = review, name = "reviewed via", sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)

    view(
        name = "active_orders",
        query = "SELECT o.id, o.status, o.placed_at, c.email FROM \"order\" o JOIN customer c ON c.id = o.customer_id WHERE o.status != 'cancelled'",
        references = listOf(order, customer),
    )

    diagram(name = "E-Commerce Schema", notation = ErmNotation.MARTIN, showIndexes = true)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `ermModel("…") { … }` | Entry-Point des ERM-Metamodells — analog zu `classDiagram`/`c4Model`/`bpmnModel`. Gibt ein `ErmModel` zurück; ohne explizites `diagram(...)` synthetisiert `build()` automatisch ein Default-Diagramm (Notation MARTIN). |
| `entity("Customer") { … }` | Deklariert eine Tabelle, gibt einen deterministischen id-String (`entity_0`, `entity_1`, …) zurück — kein UUID, damit Snapshot-Diffs stabil bleiben. |
| `id()` | Komfortfunktion für eine `NOT NULL`-Primärschlüsselspalte, Default-Typ `UUID`. |
| `attribute(name, type, primaryKey, nullable, unique, default, autoIncrement)` | Allgemeine Spaltendeklaration. |
| `foreignKey(name, references, onDelete, nullable)` | Fremdschlüsselspalte — leitet den Spaltentyp vom (Single-Column-)Primärschlüssel der Zielentität ab; Fallback `UUID`, falls die Zielentität noch nicht deklariert ist oder einen zusammengesetzten Schlüssel hat. **Deshalb muss die referenzierte Entität immer vor der referenzierenden deklariert sein** — die Reihenfolge oben (Customer → Address → Category → Product → Order → OrderItem → Payment → Review) erfüllt das durchgängig. |
| `relationship(from, to, name, sourceCardinality, targetCardinality, kind, sourceRole, targetRole)` | Zeichnet die eigentliche, gerenderte Kante zwischen zwei Entitäten (per id). **Wichtig**: `foreignKey()` allein erzeugt noch keine Kante im Diagramm — die ERM-Layout-Bridge liest ausschließlich `model.relationships`. Jede FK-Spalte oben hat deshalb ihr eigenes `relationship(...)`. |
| `index(vararg attributeNames, unique, name)` | Deklariert einen Index über bereits deklarierte Spalten (nach Name aufgelöst). |
| `check(expression, name)` | Deklariert einen `CHECK`-Constraint mit einem dialektneutralen SQL-Boolean-Ausdruck. |
| `view(name, query, references)` | Deklariert eine First-Class-Datenbankview mit rohem `SELECT`-Body und optionaler Liste referenzierter Entitäten (für Constraint-Checks, nicht geparst). |
| `diagram(name, notation, showViews, showIndexes)` | Diagramm-Projektion über das Modell; `notation` wählt zwischen `MARTIN`/`BACHMAN`/`CHEN`/`IDEF1X`. |

## Abgedeckte relationale Muster

- **Mehrere Fremdschlüssel auf dieselbe Tabelle**: `Order.billing_address_id` und `Order.shipping_address_id` referenzieren beide `Address` — zwei separate `relationship(...)`-Aufrufe mit unterschiedlichem `targetRole` (`"billing"`/`"shipping"`) und unterschiedlichem Namen (`"bills"`/`"ships"`), damit die beiden Crow's-Foot-Kanten im Rendering unterscheidbar bleiben.
- **Self-Reference**: `Category.parent_id` verweist auf die eigene Tabelle. Da der von `entity(...)` zurückgegebene id-String im eigenen Block noch nicht existiert, wird `parent_id` als normales `attribute(...)` deklariert und die Kante erst danach per `relationship(from = category, to = category, …)` gezogen — kein `foreignKey()` im eigenen Entity-Block.
- **Identifizierende Beziehung / schwache Entität**: `OrderItem` ist `weak = true` und über eine `RelationshipKind.IDENTIFYING`-Beziehung mit `Order` verbunden (`onDelete = ReferentialAction.CASCADE` an der FK-Spalte) — die Martin-Notation zeichnet das als doppelt umrandete Box (schwache Entität) mit durchgezogener statt gestrichelter Verbindungslinie.
- **Mehrfach-Referenzen auf drei verschiedene Ziele**: `Review` trägt drei Fremdschlüssel (`customer_id`, `product_id`, `order_id`) auf drei unterschiedliche Entitäten — jede mit eigener `relationship(...)`-Kante.

## ERM-First-Class-Features (Views/Indizes/Checks)

Anders als bei UML-basierten Persistenz-Workarounds (vgl. [[38 UML Profil – Exposed]]) sind Views, Indizes und Check-Constraints hier **First-Class-Modellelemente**, kein Stereotyp-Tag:

- `Product.check("price >= 0")` und `Review.check("rating BETWEEN 1 AND 5")` — dialektneutrale SQL-Boolean-Ausdrücke, entity-eingebettet.
- `Product.index("sku", unique = true)` und `Order.index("status")` — werden im gerenderten Diagramm sichtbar, weil `diagram(..., showIndexes = true)` gesetzt ist.
- `view("active_orders", query = "SELECT …", references = listOf(order, customer))` — eine First-Class-Datenbankview, die `ErmConstraintChecker` gegen dangling References prüfen kann, ohne die SQL selbst zu parsen.

## Mögliche Erweiterungen

- **Weitere Notationen (Chen/Bachman/IDEF1X)**: Das Modell ist notationsunabhängig und alle drei Renderer sind bereits produktionsreif (V3.4.3–V3.4.5) — `diagram(name = "…", notation = ErmNotation.CHEN)` (oder `BACHMAN`/`IDEF1X`) auf demselben `ermModel(...)` projiziert dieselben Entitäten/Kanten sofort in der jeweils anderen Notation, ohne Modelländerung. `category(...)` (IDEF1X-Subtyp-Cluster) wurde hier bewusst **nicht** eingebaut, weil es kein Martin-Konstrukt ist — siehe `kuml-io-svg`-Tests für `ErmChenLayoutBridge`/`ErmIdef1xLayoutBridge`.
- **`kuml reverse --format sql`**: Seit V3.4.9 kann dasselbe Modell auch aus echtem SQL-DDL rekonstruiert werden (SQL → ERM-DSL-Quelltext) — siehe [[02 Projekte/kUML V3.4]].
- **`kuml generate --plugin sql`**: Der ERM-zu-SQL-Codegenerator (V3.4.7, PostgreSQL-Dialekt zuerst) erzeugt aus genau diesem Modell direkt `CREATE TABLE`-Statements inkl. FK/Index/Check.

## Verwandte Beispiele

- [[38 UML Profil – Exposed]] — der ältere UML-Dual-Annotations-Workaround (`«Table»`/`«Entity»`), den das ERM-Metamodell ablöst
- [[03 Bereiche/kUML/Bewertung kUML vs PlantUML vs Mermaid (DB-Modellierung)]] — Vergleichsbewertung, für die dieses neutrale Schema als Webseiten-Beispiel gedacht ist
- [[02 Projekte/kUML V3.4]] — Projekt-Tracking der ERM-Welle (Metamodell, DSL, Renderer, Transformer, Reverse-Engineering)
