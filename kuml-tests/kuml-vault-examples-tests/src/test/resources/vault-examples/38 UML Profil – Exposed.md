---
title: UML Profildiagramm – Exposed Profile
date: 2026-07-08
tags:
  - kUML
  - beispiel
  - uml
  - profil
  - exposed
  - persistenz
  - sql
  - mda
status: aktiv
---

# UML Profildiagramm — Exposed Profile

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Das **Exposed-Profil** (`dev.kuml.profiles.exposed`) bringt Kotlin-Exposed-ORM-Konzepte als First-Class-Stereotypen ins UML-Klassendiagramm: `«Table»`, `«Column»` und `«FK»`. Es ist der zentrale Baustein der **MDA-Persistenzschicht** aus [[03 Bereiche/kUML/ADR/ADR-0016 UML-zu-Exposed-Pipeline (MDA-Persistenzschicht)|ADR-0016]] — ein UML-Klassenmodell (PIM) wird automatisch in ein annotiertes, **renderbares** PSM transformiert, aus dem sowohl SQL-DDL als auch Kotlin-Exposed-Code entstehen. Treibender Anwendungsfall: die Persistenzschicht von Pepela Portal und (übertragen) Lapis Cloud.

## Profil-Diagramm

```kuml
profileDiagram(name = "Exposed Profile") {
    val classMC = metaclass(name = "Class")
    val propertyMC = metaclass(name = "Property")
    val associationMC = metaclass(name = "Association")

    val table = stereotype(name = "Table", metaclasses = listOf("Class")) {
        tag(name = "tableName", type = "String")
        tag(name = "schema", type = "String")
    }

    val column = stereotype(name = "Column", metaclasses = listOf("Property")) {
        tag(name = "columnType", type = "String")
        tag(name = "name", type = "String")
    }

    val fk = stereotype(name = "FK", metaclasses = listOf("Association")) {
        tag(name = "targetTable", type = "String")
    }

    extension(stereotype = table, metaclass = classMC)
    extension(stereotype = column, metaclass = propertyMC)
    extension(stereotype = fk, metaclass = associationMC)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `metaclass(name = "Property")` | Verweis auf eine UML-Metaklasse. `«Column»` erweitert `Property` (Attribute), `«FK»` erweitert `Association` — nicht das einzelne Assoziationsende. |
| `stereotype(name = "Table", metaclasses = listOf("Class")) { … }` | Deklariert den `«Table»`-Stereotyp mit seinen Tagged-Values. |
| `tag(name = "tableName", type = "String")` | Tagged-Value-Definition — `tableName`/`schema` bei `Table`, `columnType`/`name` bei `Column`, `targetTable` bei `FK`. |
| `extension(stereotype = …, metaclass = …)` | `«extension»`-Dependency zwischen Stereotyp und Metaklasse. |

Im echten Kotlin-Profil (`ExposedProfile.kt`) trägt `Table` zusätzlich einen Default für `schema` (`"public"`), und das Profil erweitert [[15 UML Profil – Java EE Profile|javaEeProfile]] via `extends(javaEeProfile)` (D12: Profil-Vererbung) — die `profileDiagram`-DSL kann Profil-Vererbung aktuell nicht als eigenes Diagrammelement darstellen, daher rein textuell hier vermerkt.

## Automatisch erzeugtes PSM (ADR-0016)

Anders als bei AUTOSAR oder Java EE wird das Exposed-Profil in der Praxis **nicht von Hand** auf ein Modell angewendet. Der M2M-Transformer `UmlToExposedPsmTransformer` (`uml-to-exposed-psm`) nimmt ein gewöhnliches UML-Klassenmodell (PIM) — z. B. eine `User`-Klasse mit `id: Long` und `name: String` — und erzeugt daraus automatisch ein annotiertes PSM:

```kotlin
val psm = UmlToExposedPsmTransformer().transform(pim, TransformContext())
// psm.output: User-Klasse trägt jetzt zwei Stereotypen:
//   «Table»  { tableName = "users" }   — semantisch korrekt, aus exposedProfile
//   «Entity» { tableName = "users" }   — kosmetisch dupliziert (JavaEE-Namespace)
// id   → «Id»                          — PK-Marker, kein «Column»
// name → «Column» { columnType = "varchar", name = "name" }
```

**Warum die Dual-Annotation?** `kuml-gen-sql`s `SqlNames.tableName()` erkennt Tabellennamen nur am wörtlichen Stereotyp-Namen `"Entity"` — unabhängig vom Namespace. Damit das PSM ohne Änderung an `SqlDdlGenerator` direkt weiterverarbeitbar bleibt, erhält jede Klasse **beide** Stereotypen nebeneinander (kein `specializes`-Verhältnis). Das `«FK»` am Assoziationsende ist rein kosmetisch fürs gerenderte Diagramm — `kuml-gen-sql` leitet Fremdschlüssel ausschließlich aus Multiplizitäten ab, nie aus Stereotypen. Jeder aus dem PIM abgeleitete Bezeichner (Tabellen-/Spaltenname) wird vor der Übernahme in ein Tag gegen SQL-Metazeichen geprüft (`UnsafeUmlNameException` bei Verstoß) — das PSM ist damit auch gegen Modell-Autoren mit böswilligen Klassennamen abgesichert.

## Renderbares Beispiel

Eine Handkodierte Illustration desselben Musters — zwei Klassen mit dem Display-Label-Mechanismus (`stereotypes += "…"`), analog zum AUTOSAR-Beispiel:

```kuml
classDiagram(name = "Exposed PSM – User & Address") {
    val address = classOf(name = "Address") {
        stereotypes += "Table"
        stereotypes += "Entity"
        attribute(name = "id", type = "Long")
        attribute(name = "city", type = "String")
    }

    val user = classOf(name = "User") {
        stereotypes += "Table"
        stereotypes += "Entity"
        attribute(name = "id", type = "Long")
        attribute(name = "name", type = "String")
    }

    association(source = user, target = address) {
        name = "billingAddress"
        target { multiplicity("1") }
    }
}
```

> [!info] Warum keine `«Column»`/`«Id»`/`«FK»`-Labels im gerenderten Beispiel?
> `ClassBuilder` hat ein einfaches, unvalidiertes `stereotypes: MutableList<String>`-Feld für Display-Labels (genutzt oben für `«Table»`/`«Entity»`). `AttributeBuilder` und `AssociationBuilder` haben dieses Feld **nicht** — Attribut- und Assoziations-Stereotypen lassen sich im `.kuml.kts`-Skript aktuell nur über den validierten, profil-gebundenen `stereotype(name) { … }`-Mechanismus setzen, der ein per `applyProfile(...)` registriertes Profil voraussetzt. Die eingebauten Profile (`exposedProfile`, `javaEeProfile`, …) stehen als Kotlin-Objekte aber nicht in den Skript-Default-Imports (`KumlScript.kt`) — sie sind für den programmatischen Gebrauch (M2M-Transformer, JVM-API) gedacht, nicht fürs Skript. Siehe „Bekannte DSL-Lücken" unten.

## Weiterverarbeitung — SQL-DDL und Exposed-Kotlin

Das PSM ist doppelt konsumierbar, ohne erneute Transformation:

| Generator | Eingabe-Stereotyp | Ergebnis (Ausschnitt) |
|---|---|---|
| `SqlDdlGenerator` (`kuml-gen-sql`) | `«Entity»{tableName}`, `«Column»{name}`, `«Id»` | `CREATE TABLE users (` … `name VARCHAR(255)` — Dialekt über `sql-dialect`-Option (`postgres`/`mysql`/`h2`/`sqlite`) |
| `FlywayBaselineGenerator` | dieselbe PSM-Eingabe | `V<version>__<description>.sql` statt `schema.sql` — dünner Wrapper um `SqlDdlGenerator` |
| `UmlToExposedTransformer` (`kuml-gen-exposed`, Variante B) | `«Table»{tableName}`, `«Column»{columnType}` | Kotlin-Exposed-`Table`-Objekt, z. B. `PrimaryKey(id)` |

Verkettet über `TransformChain<KumlDiagram, KumlDiagram, List<GeneratedFile>>(UmlToExposedPsmTransformer(), UmlToExposedTransformer())` — Chain-ID `uml-to-exposed-psm+uml-to-exposed` — entsteht die vollständige Pipeline **PIM → PSM → Kotlin-Exposed-Quelltext** in einem Aufruf, ohne Zwischenmodell in Git (ADR-0016, Entscheidung 1).

## Built-in vs. Custom Profile

| | Built-in `dev.kuml.profiles.exposed` | Eigenes Custom-Profil |
|---|---|---|
| **Bezug** | Teil von kUML, kein Setup nötig | Per JAR, Maven-Koordinaten oder Inline-DSL |
| **Stereotypen** | `Table`, `Column`, `FK` | Frei definierbar |
| **Anwenden (programmatisch)** | `UmlToExposedPsmTransformer` (automatisch) oder `applyProfile(exposedProfile)` in Kotlin-Code | `applyProfile(myProfile)` — nach `ProfileRegistry`-Registrierung |
| **Anwenden (im `.kuml.kts`-Skript)** | `stereotypes += "Table"` (Display-Label, nur Klassenebene) | Identisch — Attribut-/Assoziationsebene derzeit ebenfalls nicht skriptfähig |

## Bekannte DSL-Lücken (Fix beschlossen)

- **Attributebene**: `AttributeBuilder` besitzt kein Display-Label-`stereotypes`-Feld — nur den profil-validierten `stereotype(name) { }`-Weg. Ein einfaches `stereotypes: MutableList<String>` analog zu `ClassBuilder` würde `«Column»`/`«Id»`-Labels auch im Skript renderbar machen.
- **Assoziationsebene**: `AssociationBuilder` implementiert `UmlElementScope` nicht und hat gar keinen Stereotyp-Mechanismus — weder validiert noch Display-Label. `«FK»` ist deshalb aktuell ausschließlich über den programmatischen `UmlToExposedPsmTransformer`-Pfad sichtbar, nie im gerenderten Skript-Beispiel.

> [!success] Kein Standard-Problem, DSL-Nachzieh-Bug — [[03 Bereiche/kUML/ADR/ADR-0017 DSL-Vollständigkeit gegenüber unterstützten Metamodell-Standards|ADR-0017]] (2026-07-08)
> Der UML-2.5-Standard erlaubt Stereotypen auf `Property` und `Association` genauso wie auf `Class`; kUMLs Metamodell trägt das bereits (`UmlProperty.appliedStereotypes`, `UmlAssociation : Stereotypable`). Die Lücke sitzt ausschließlich in der Skript-DSL-Komfortschicht. Per ADR-0017 gilt das als **zu behebender Bug**, nicht als optionale Erweiterung — nachgezogen in Hintergrund-Aufgabe `task_b52cc45e`. Nach dem Fix sollte das „Renderbares Beispiel" oben um `«Column»`/`«Id»`/`«FK»`-Labels ergänzt werden.

## Ausblick — ERM (V3.4)

Der hier gezeigte Dual-Annotations-Mechanismus (`«Table»` + `«Entity»` nebeneinander, damit `kuml-gen-sql` den Tabellennamen erkennt) ist ein bewusster Workaround, kein Zielzustand. [[02 Projekte/kUML V3.4]] löst ihn durch ein eigenständiges **ERM-Metamodell** ab: `kuml-transform-uml-to-erm` übernimmt die Rolle von `UmlToExposedPsmTransformer`, aber mit einem typisierten ERM-PSM statt einem doppelt-stereotypisierten UML-PSM — inklusive eigener Notationen (Martin/Bachman/Chen/IDEF1X) und direktem Renderpfad für Views/Indizes/Check-Constraints, die im UML-basierten PSM heute keinen Platz haben.

## Mögliche Erweiterungen

- **Views/Indizes/Check-Constraints**: kommen mit dem ERM-Metamodell in V3.4 als First-Class-Elemente — im UML-basierten PSM hier bewusst nicht nachgebildet.
- **Weitere Dialekte**: `SqlDdlGenerator` unterstützt MySQL/H2/SQLite bereits über dieselbe `sql-dialect`-Option — ungenutzt in diesem Beispiel, das PostgreSQL (Default) zeigt.
- **DSL-Lücken schließen** *(beschlossen, ADR-0017)*: Display-Label-`stereotypes`-Feld für `AttributeBuilder` und `AssociationBuilder` (siehe oben) — schließt die Lücke zwischen „skriptbarem Beispiel" und „automatisch erzeugtem PSM".

## Verwandte Beispiele

- [[15 UML Profil – Java EE Profile]] — Basis-Profil, von `exposedProfile` erweitert
- [[29 UML Profil – AUTOSAR]] — identischer `profileDiagram`-Mechanismus, andere Domäne
- [[01 UML Klasse – Order Domain]] — Klassendiagramm ohne Profil-Anreicherung
- [[03 Bereiche/kUML/ADR/ADR-0016 UML-zu-Exposed-Pipeline (MDA-Persistenzschicht)]] — vollständige Entscheidung + Umsetzungsstand
- [[03 Bereiche/kUML/ADR/ADR-0017 DSL-Vollständigkeit gegenüber unterstützten Metamodell-Standards]] — Grundsatzentscheidung zur hier gefundenen DSL-Lücke
- [[02 Projekte/kUML V3.4]] — ERM als Nachfolger des Dual-Annotations-Workarounds
- [[03 Bereiche/kUML/Profile System]] — technische Referenz aller Built-in-Profile
