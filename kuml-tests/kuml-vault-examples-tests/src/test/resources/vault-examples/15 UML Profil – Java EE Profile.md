---
title: UML Profildiagramm – Java EE Profile
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - uml
  - profil
status: aktiv
---

# UML Profildiagramm — Java EE Profile

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **UML-Profildiagramm** *erweitert* das UML-Metamodell: es deklariert eigene Stereotypen und Tagged-Values, mit denen domänenspezifische Konzepte (hier: Java-EE-Persistenz-Annotationen) ausgedrückt werden können. Ergebnis: `«Entity»` und `«ValueObject»` werden zu first-class Klassifikatoren in eigenen Klassendiagrammen.

## Diagramm

```kuml
profileDiagram(name = "Java EE Profile") {
    val classMC = metaclass(name = "Class")

    val entity = stereotype(name = "Entity", metaclasses = listOf("Class")) {
        tag(name = "tableName", type = "String")
        tag(name = "schema", type = "String")
    }

    val valueObject = stereotype(name = "ValueObject", metaclasses = listOf("Class")) {
        tag(name = "immutable", type = "Boolean")
    }

    extension(stereotype = entity, metaclass = classMC)
    extension(stereotype = valueObject, metaclass = classMC)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `profileDiagram(name = …) { … }` | Top-Level: erzeugt ein UML-Profil. |
| `metaclass(name = "Class")` | Verweis auf ein UML-Metaklassen-Element (`«metaclass»`-Stereotyp am Klassifikator). |
| `stereotype(name = …, metaclasses = listOf(…)) { … }` | Deklariert einen Stereotyp und welche Metaklassen er erweitern kann. |
| `tag(name = "tableName", type = "String")` | Tagged-Value-Definition — die Properties, die der Stereotyp einbringt. |
| `extension(stereotype = …, metaclass = …)` | `«extension»`-Dependency zwischen Stereotyp und Metaklasse. |

## Anwenden des Profils

In einem Klassendiagramm:

```kotlin
classDiagram(name = "Persistence Layer") {
    classOf(name = "Customer", stereotypes = listOf("Entity")) {
        attribute(name = "id", type = "UUID")
    }
}
```

## Mögliche Erweiterungen

- **Profil-Paket**: `packageOf(name = "javaee") { stereotype(name = "Entity") { … } }`
- **Mehrere Metaklassen pro Stereotyp**: `metaclasses = listOf("Class", "Property")`
- **Constraint an einem Tag**: per Constraint-DSL ergänzen

## Verwandte Beispiele

- [[01 UML Klasse – Order Domain]] — typischer Anwender eines Profils
- [[11 UML Paket – Domain Modules]] — Profil als Paket bündeln
