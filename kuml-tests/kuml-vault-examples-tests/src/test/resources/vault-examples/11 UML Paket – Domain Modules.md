---
title: UML Paketdiagramm – Domain Modules
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - paketdiagramm
status: aktiv
---

# UML Paketdiagramm — Domain Modules

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Paketdiagramm** zeigt die Modulstruktur eines Modells — welche Pakete es gibt, was sie enthalten und wie sie voneinander abhängen. Hier: drei Domänen-Pakete (`shop`, `payment`, `shared`) mit `«import»`- und `«access»`-Stereotypen zwischen ihnen.

## Diagramm

```kuml
packageDiagram(name = "Order Domain Modules") {
    val shared = packageOf(name = "shared") {
        classOf(name = "Money") {
            attribute(name = "amount", type = "BigDecimal")
            attribute(name = "currency", type = "String")
        }
    }

    val shop = packageOf(name = "shop") {
        classOf(name = "Customer")
        classOf(name = "Order")
    }

    val payment = packageOf(name = "payment") {
        classOf(name = "Invoice")
        classOf(name = "Receipt")
    }

    packageImport(client = shop, supplier = shared)
    packageImport(client = payment, supplier = shared)
    packageAccess(client = payment, supplier = shop)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `packageDiagram(name = …) { … }` | Top-Level: erzeugt ein Paketdiagramm. |
| `packageOf(name = …) { … }` | Erzeugt ein UML-Paket. Inhalte (Klassen, Interfaces, Enums, geschachtelte Pakete) werden im Block aufgenommen. |
| `classOf(name = …)` innerhalb `packageOf` | Klasse als Mitglied des Pakets. ID wird automatisch qualifiziert (`shared::Money`). |
| `packageImport(client = …, supplier = …)` | `«import»`-Abhängigkeit — der Client *übernimmt* die public Members. |
| `packageMerge(client = …, supplier = …)` | `«merge»`-Abhängigkeit — die Pakete werden vereinigt. |
| `packageAccess(client = …, supplier = …)` | `«access»`-Abhängigkeit — schwächer als `import`. |

## Display-Optionen

```kotlin
packageDiagram(name = "…") {
    showStereotypes = true   // «import» / «merge» / «access» Labels
    showFolderTabs  = true   // klassische Reiter-Optik
    // … Pakete + Dependencies …
}
```

## Mögliche Erweiterungen

- **Geschachtelte Pakete**: `packageOf(name = "shop") { packageOf(name = "ordering") { … } }`
- **Profile anwenden**: `applyProfile(javaEEProfile)` aus [[15 UML Profil – Java EE Profile]]
- **Interfaces/Enums**: zusätzlich `interfaceOf(…)` und `enumOf(…)` innerhalb der Pakete

## Verwandte Beispiele

- [[01 UML Klasse – Order Domain]] — Inhalte eines Pakets im Detail
- [[12 UML Component – Order Architecture]] — eine Stufe gröber: Components statt Klassen
- [[15 UML Profil – Java EE Profile]] — Stereotypen, die in einem Paket angewandt werden
