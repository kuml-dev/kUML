---
title: ERM – Notiz-Arten
date: 2026-11-03
tags:
  - kUML
  - beispiel
  - erm
  - notiz
status: aktiv
---

# ERM – Notiz-Arten

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Notizen mit Art am Entity-Relationship-Diagramm: `note` verankert eine Notiz an einer oder mehreren Entitäten und nimmt ein `kind` entgegen. (PAL-Welle N1)

## Diagramm

```kuml
ermModel(name = "Note kinds") {
    val customer =
        entity(name = "Customer") {
            id()
            attribute(name = "email", type = ErmDataType.Varchar(255), unique = true, nullable = false)
        }
    val order =
        entity(name = "Order") {
            id()
            foreignKey(name = "customer_id", references = customer, nullable = false)
            attribute(name = "total", type = ErmDataType.Decimal(precision = 10, scale = 2))
        }
    relationship(from = customer, to = order, sourceCardinality = Cardinality.ONE, targetCardinality = Cardinality.ZERO_MANY)
    note(text = "A plain note without a symbol.", customer)
    note(text = "Hint: index the email column.", customer, kind = NoteKind.HINT)
    note(text = "Warning: totals are not rounded here.", order, kind = NoteKind.WARNING)
    note(text = "Success: all foreign keys are checked.", order, kind = NoteKind.SUCCESS)
}
```

## Was gezeigt wird

| Konstrukt | Bedeutung |
|---|---|
| `note(text, entity, kind = NoteKind.X)` | Notiz an einer Entität, mit Art |
| `HINT`, `WARNING`, `SUCCESS` | Drei Arten mit Symbol und Streifen, daneben eine einfache Notiz |
| Gepunktete Anker | Die Verbindung zur Entität bleibt auch bei Symbolen erhalten |
