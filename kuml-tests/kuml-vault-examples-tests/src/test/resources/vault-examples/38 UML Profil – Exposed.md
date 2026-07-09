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
        attribute(name = "id", type = "Long") {
            stereotypes += "Id"
        }
        attribute(name = "city", type = "String") {
            stereotypes += "Column"
        }
    }

    val user = classOf(name = "User") {
        stereotypes += "Table"
        stereotypes += "Entity"
        attribute(name = "id", type = "Long") {
            stereotypes += "Id"
        }
        attribute(name = "name", type = "String") {
            stereotypes += "Column"
        }
    }

    association(source = user, target = address) {
        name = "billingAddress"
        stereotypes += "FK"
        target { multiplicity("1") }
    }
}
```

> [!success] `«Column»`/`«Id»`/`«FK»`-Labels seit v0.27.0 (2026-07-09) renderbar
> Behoben in Commit `a1fb2b8` („kUML DSL-Stereotypen — Attribut-/Assoziations-Stereotypen in DSL, Rendering und Layout"), Teil des heutigen v0.27.0-Release. `AttributeBuilder` und `AssociationBuilder` tragen jetzt dasselbe unvalidierte Display-Label-Feld `stereotypes: MutableList<String>` wie `ClassBuilder`; `StereotypeHelper` rendert es im SVG-Output, `UmlContentSizeProvider` berücksichtigt es bei der Knotengrößenberechnung. Das obige Skript wurde entsprechend aktualisiert und mit `kuml_render` verifiziert — `«Id»`/`«Column»` erscheinen jetzt vor den Attributzeilen, `«FK»` an der Assoziationskante. Der validierte, profil-gebundene `stereotype(name) { … }`-Weg (siehe unten) bleibt davon unberührt und ist weiterhin nur programmatisch nutzbar.

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
| **Anwenden (im `.kuml.kts`-Skript)** | `stereotypes += "Table"` / `"Column"` / `"FK"` (Display-Label, seit v0.27.0 auf Klassen-, Attribut- und Assoziationsebene) | Identisch |

## Bekannte DSL-Lücken (behoben, v0.27.0)

- ~~**Attributebene**: `AttributeBuilder` besitzt kein Display-Label-`stereotypes`-Feld — nur den profil-validierten `stereotype(name) { }`-Weg.~~ Behoben: `AttributeBuilder` hat jetzt ein `stereotypes: MutableList<String>`-Feld analog zu `ClassBuilder`, `«Column»`/`«Id»`-Labels sind im Skript renderbar.
- ~~**Assoziationsebene**: `AssociationBuilder` hat gar keinen Stereotyp-Mechanismus.~~ Behoben: `AssociationBuilder` trägt ebenfalls ein `stereotypes`-Display-Label-Feld. `«FK»` ist jetzt auch im gerenderten Skript-Beispiel sichtbar, nicht mehr nur über den programmatischen `UmlToExposedPsmTransformer`-Pfad.

> [!success] Kein Standard-Problem, DSL-Nachzieh-Bug — [[03 Bereiche/kUML/ADR/ADR-0017 DSL-Vollständigkeit gegenüber unterstützten Metamodell-Standards|ADR-0017]] (2026-07-08), behoben 2026-07-09
> Der UML-2.5-Standard erlaubt Stereotypen auf `Property` und `Association` genauso wie auf `Class`; kUMLs Metamodell trug das bereits (`UmlProperty.appliedStereotypes`, `UmlAssociation : Stereotypable`). Die Lücke saß ausschließlich in der Skript-DSL-Komfortschicht. Per ADR-0017 galt das als **zu behebender Bug**, nicht als optionale Erweiterung — umgesetzt in Commit `a1fb2b8` („kUML DSL-Stereotypen"), Teil von **v0.27.0** (2026-07-09). Das „Renderbares Beispiel" oben wurde entsprechend um `«Column»`/`«Id»`/`«FK»`-Labels ergänzt und mit `kuml_render` verifiziert.

## Ausblick — ERM (V3.4)

Der hier gezeigte Dual-Annotations-Mechanismus (`«Table»` + `«Entity»` nebeneinander, damit `kuml-gen-sql` den Tabellennamen erkennt) ist ein bewusster Workaround, kein Zielzustand. [[02 Projekte/kUML V3.4]] löst ihn durch ein eigenständiges **ERM-Metamodell** ab: `kuml-transform-uml-to-erm` übernimmt die Rolle von `UmlToExposedPsmTransformer`, aber mit einem typisierten ERM-PSM statt einem doppelt-stereotypisierten UML-PSM — inklusive eigener Notationen (Martin/Bachman/Chen/IDEF1X) und direktem Renderpfad für Views/Indizes/Check-Constraints, die im UML-basierten PSM heute keinen Platz haben.

## Mögliche Erweiterungen

- **Views/Indizes/Check-Constraints**: kommen mit dem ERM-Metamodell in V3.4 als First-Class-Elemente — im UML-basierten PSM hier bewusst nicht nachgebildet.
- **Weitere Dialekte**: `SqlDdlGenerator` unterstützt MySQL/H2/SQLite bereits über dieselbe `sql-dialect`-Option — ungenutzt in diesem Beispiel, das PostgreSQL (Default) zeigt.
- ~~**DSL-Lücken schließen** *(beschlossen, ADR-0017)*~~ — erledigt in v0.27.0 (siehe oben): Display-Label-`stereotypes`-Feld für `AttributeBuilder` und `AssociationBuilder` schließt die Lücke zwischen „skriptbarem Beispiel" und „automatisch erzeugtem PSM".

## Verwandte Beispiele

- [[15 UML Profil – Java EE Profile]] — Basis-Profil, von `exposedProfile` erweitert
- [[29 UML Profil – AUTOSAR]] — identischer `profileDiagram`-Mechanismus, andere Domäne
- [[01 UML Klasse – Order Domain]] — Klassendiagramm ohne Profil-Anreicherung
- [[03 Bereiche/kUML/ADR/ADR-0016 UML-zu-Exposed-Pipeline (MDA-Persistenzschicht)]] — vollständige Entscheidung + Umsetzungsstand
- [[03 Bereiche/kUML/ADR/ADR-0017 DSL-Vollständigkeit gegenüber unterstützten Metamodell-Standards]] — Grundsatzentscheidung zur hier gefundenen DSL-Lücke
- [[02 Projekte/kUML V3.4]] — ERM als Nachfolger des Dual-Annotations-Workarounds
- [[03 Bereiche/kUML/Profile System]] — technische Referenz aller Built-in-Profile
