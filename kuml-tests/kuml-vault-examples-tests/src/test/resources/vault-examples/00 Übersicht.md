---
title: kUML Beispiele – Übersicht
date: 2026-06-14
tags:
  - kUML
  - beispiele
  - referenz
status: aktiv
---

# kUML Beispiele

Sammlung lebender Beispielnotizen, die das [[02 Projekte/kUML V2.0]]-Plugin [[02 Projekte/kUML V2.0#Obsidian-Plugin (obsidian-kuml)|obsidian-kuml]] in diesem Vault demonstriert. Jede Notiz enthält einen ` ```kuml `-Block, der durch die kUML-Render-Pipeline läuft (entweder über `kuml serve` oder via CLI-Fallback).

> [!tip] Voraussetzung
> Das Plugin **obsidian-kuml** muss aktiviert sein und entweder `kuml serve` muss auf `http://localhost:4242` laufen, oder das `kuml`-Binary muss im PATH liegen. Siehe [[02 Projekte/kUML V2.0#Obsidian-Plugin (obsidian-kuml)]].

## Vollständige Abdeckung

Die Sammlung deckt **alle 28 Diagrammtypen** der drei klassischen in kUML unterstützten Modellierungssprachen ab — UML 2.x (14), C4 (6) und SysML 2 (8). Hinzu kommen seit V3.1 **BPMN 2.0**-Beispiele (Process + Collaboration + Conversation) als vierte Modellierungssprache, **profil-spezifische Vertiefungsbeispiele** für die Built-in-Profile sowie seit V3.4 das **ERM-Metamodell** (Entity-Relationship, alle vier Notationen Martin/Bachman/Chen/IDEF1X) als fünfte Modellierungssprache.

### UML 2.x (14 Diagrammtypen)

| # | Notiz | Familie |
|---|---|---|
| 01 | [[01 UML Klasse – Order Domain]] | Strukturell |
| 10 | [[10 UML Objekt – Order Snapshot]] | Strukturell |
| 11 | [[11 UML Paket – Domain Modules]] | Strukturell |
| 12 | [[12 UML Component – Order Architecture]] | Strukturell |
| 13 | [[13 UML Composite Structure – Order Internals]] | Strukturell |
| 14 | [[14 UML Deployment – Cloud Stack]] | Strukturell |
| 15 | [[15 UML Profil – Java EE Profile]] | Strukturell |
| 16 | [[16 UML Use Case – Online Shop]] | Verhalten |
| 17 | [[17 UML Activity – Checkout Flow]] | Verhalten |
| 18 | [[18 UML State Machine – Order Lifecycle]] | Verhalten |
| 19 | [[19 UML Sequence – API Submit]] | Verhalten (Interaktion) |
| 20 | [[20 UML Communication – Place Order]] | Verhalten (Interaktion) |
| 21 | [[21 UML Timing – TCP Handshake]] | Verhalten (Interaktion) |
| 22 | [[22 UML Interaction Overview – Order Process]] | Verhalten (Interaktion) |

### Profil-Vertiefungen (Built-in-Profile)

| # | Notiz | Profil | Domäne |
|---|---|---|---|
| 29 | [[29 UML Profil – AUTOSAR]] | `dev.kuml.profiles.autosar` | Automotive / Embedded |
| 38 | [[38 UML Profil – Exposed]] | `dev.kuml.profiles.exposed` | Persistenz / MDA (ADR-0016) |

### C4 (6 Diagrammtypen)

| # | Notiz | Ebene |
|---|---|---|
| 23 | [[23 C4 Context – Internet Banking]] | 1 — System Context |
| 02 | [[02 C4 Container – Internet Banking]] | 2 — Container |
| 24 | [[24 C4 Component – Web App Internals]] | 3 — Component |
| 25 | [[25 C4 Deployment – AWS Production]] | 4 — Deployment |
| 26 | [[26 C4 Dynamic – Checkout Flow]] | 4 — Dynamic |
| 09 | [[09 C4 Landscape – Enterprise Banking]] | Enterprise — Landscape |

### SysML 2 (8 Diagrammtypen)

| # | Notiz | Familie |
|---|---|---|
| 03 | [[03 SysML 2 BDD – Hybrid Vehicle]] | Strukturell |
| 27 | [[27 SysML 2 IBD – Hybrid Vehicle Wiring]] | Strukturell |
| 28 | [[28 SysML 2 PAR – Newton]] | Constraint |
| 05 | [[05 SysML 2 UC – Library System]] | Use-Case |
| 08 | [[08 SysML 2 REQ – Vehicle Requirements]] | Anforderungen |
| 04 | [[04 SysML 2 STM – Traffic Light]] | Verhalten |
| 07 | [[07 SysML 2 ACT – Order Processing]] | Verhalten |
| 06 | [[06 SysML 2 SEQ – Login Flow]] | Verhalten (Interaktion) |

### BPMN 2.0 (seit V3.1)

| # | Notiz | Säule |
|---|---|---|
| 30 | [[30 BPMN Process – Order Fulfillment]] | Process — Events, Gateways, Tasks |
| 31 | [[31 BPMN Process – Sub-Process Loop]] | Process — Sub-Process + Boundary-Event |
| 32 | [[32 BPMN Collaboration – Customer und Supplier]] | Collaboration — Pools + MessageFlows |
| 36 | [[36 BPMN Conversation – PdV Kommunikation]] | Conversation — Participants + Hexagons (V3.2.3) |

### ERM (seit V3.4)

| # | Notiz | Notation |
|---|---|---|
| 39 | [[39 ERM Martin – E-Commerce Schema]] | Martin / Crow's Foot, Bachman, Chen, IDEF1X (alle vier seit 2026-07-10 in einer Notiz) |

## Empfohlene Lese-Reihenfolge nach Komplexität

| Stufe | Notiz | Diagrammtyp |
|---|---|---|
| **Einsteiger** | [[01 UML Klasse – Order Domain]] | UML Class |
| **Einsteiger** | [[02 C4 Container – Internet Banking]] | C4 Container |
| **Einsteiger** | [[23 C4 Context – Internet Banking]] | C4 System Context |
| **Einsteiger** | [[10 UML Objekt – Order Snapshot]] | UML Object |
| **Einsteiger** | [[16 UML Use Case – Online Shop]] | UML Use Case |
| **Einsteiger** | [[18 UML State Machine – Order Lifecycle]] | UML State Machine |
| **Mittel** | [[11 UML Paket – Domain Modules]] | UML Package |
| **Mittel** | [[12 UML Component – Order Architecture]] | UML Component |
| **Mittel** | [[17 UML Activity – Checkout Flow]] | UML Activity |
| **Mittel** | [[19 UML Sequence – API Submit]] | UML Sequence |
| **Mittel** | [[20 UML Communication – Place Order]] | UML Communication |
| **Mittel** | [[21 UML Timing – TCP Handshake]] | UML Timing |
| **Mittel** | [[03 SysML 2 BDD – Hybrid Vehicle]] | SysML 2 BDD |
| **Mittel** | [[04 SysML 2 STM – Traffic Light]] | SysML 2 STM |
| **Mittel** | [[05 SysML 2 UC – Library System]] | SysML 2 UC |
| **Mittel** | [[24 C4 Component – Web App Internals]] | C4 Component |
| **Mittel** | [[26 C4 Dynamic – Checkout Flow]] | C4 Dynamic |
| **Fortgeschritten** | [[13 UML Composite Structure – Order Internals]] | UML Composite Structure |
| **Fortgeschritten** | [[14 UML Deployment – Cloud Stack]] | UML Deployment |
| **Fortgeschritten** | [[15 UML Profil – Java EE Profile]] | UML Profile |
| **Fortgeschritten** | [[29 UML Profil – AUTOSAR]] | UML Profile (AUTOSAR) |
| **Fortgeschritten** | [[38 UML Profil – Exposed]] | UML Profile (Exposed/MDA) |
| **Fortgeschritten** | [[22 UML Interaction Overview – Order Process]] | UML Interaction Overview |
| **Fortgeschritten** | [[06 SysML 2 SEQ – Login Flow]] | SysML 2 SEQ |
| **Fortgeschritten** | [[07 SysML 2 ACT – Order Processing]] | SysML 2 ACT |
| **Fortgeschritten** | [[08 SysML 2 REQ – Vehicle Requirements]] | SysML 2 REQ |
| **Fortgeschritten** | [[09 C4 Landscape – Enterprise Banking]] | C4 Landscape |
| **Fortgeschritten** | [[25 C4 Deployment – AWS Production]] | C4 Deployment |
| **Fortgeschritten** | [[27 SysML 2 IBD – Hybrid Vehicle Wiring]] | SysML 2 IBD |
| **Fortgeschritten** | [[28 SysML 2 PAR – Newton]] | SysML 2 PAR |
| **Mittel** | [[30 BPMN Process – Order Fulfillment]] | BPMN Process |
| **Fortgeschritten** | [[31 BPMN Process – Sub-Process Loop]] | BPMN Sub-Process |
| **Fortgeschritten** | [[32 BPMN Collaboration – Customer und Supplier]] | BPMN Collaboration |
| **Fortgeschritten** | [[39 ERM Martin – E-Commerce Schema]] | ERM Martin |

## Zuordnung Vault → kuml.dev-Playground

Der [Playground auf kuml.dev](https://kuml.dev/playground) speist seine Beispiele aus `playground-sources/<key>.kuml.kts` im Repo `kuml-dev/kuml.dev`. **Diese Vault-Notizen sind die Single Source of Truth** — wenn der ` ```kuml `-Block hier geändert wird, muss die Playground-Datei nachgezogen werden (siehe CLAUDE.md → "kuml.dev-Playground-Beispiele synchron zum Vault halten").

| # | Vault-Notiz | Playground-Key (`playground-sources/<key>.kuml.kts`) |
|---|---|---|
| 01 | [[01 UML Klasse – Order Domain]] | `uml-class-order-domain` |
| 02 | [[02 C4 Container – Internet Banking]] | `c4-container-banking` |
| 03 | [[03 SysML 2 BDD – Hybrid Vehicle]] | `sysml2-hybrid-vehicle` |
| 04 | [[04 SysML 2 STM – Traffic Light]] | `sysml2-traffic-light-stm` |
| 05 | [[05 SysML 2 UC – Library System]] | `sysml2-library-system-uc` |
| 06 | [[06 SysML 2 SEQ – Login Flow]] | `sysml2-login-flow-seq` |
| 07 | [[07 SysML 2 ACT – Order Processing]] | `sysml2-order-processing-act` |
| 08 | [[08 SysML 2 REQ – Vehicle Requirements]] | `sysml2-vehicle-requirements` |
| 09 | [[09 C4 Landscape – Enterprise Banking]] | `c4-landscape-enterprise-banking` |
| 10 | [[10 UML Objekt – Order Snapshot]] | `uml-object-order-snapshot` |
| 11 | [[11 UML Paket – Domain Modules]] | `uml-package-layered-architecture` |
| 12 | [[12 UML Component – Order Architecture]] | `uml-component-architecture` |
| 13 | [[13 UML Composite Structure – Order Internals]] | `uml-composite-payment-service` |
| 14 | [[14 UML Deployment – Cloud Stack]] | `uml-deployment-order-system` |
| 15 | [[15 UML Profil – Java EE Profile]] | `uml-profile-jpa-annotations` |
| 16 | [[16 UML Use Case – Online Shop]] | `uml-usecase-checkout` |
| 17 | [[17 UML Activity – Checkout Flow]] | `uml-activity-order-checkout` |
| 18 | [[18 UML State Machine – Order Lifecycle]] | `uml-state-order-lifecycle` |
| 19 | [[19 UML Sequence – API Submit]] | `uml-sequence-place-order` |
| 20 | [[20 UML Communication – Place Order]] | `uml-communication-checkout` |
| 21 | [[21 UML Timing – TCP Handshake]] | `uml-timing-tcp-handshake` |
| 22 | [[22 UML Interaction Overview – Order Process]] | `uml-interaction-overview-order-checkout` |
| 23 | [[23 C4 Context – Internet Banking]] | `c4-context-internet-banking` |
| 24 | [[24 C4 Component – Web App Internals]] | *(noch kein Playground-Eintrag — Lücke)* |
| 25 | [[25 C4 Deployment – AWS Production]] | *(noch kein Playground-Eintrag — Lücke)* |
| 26 | [[26 C4 Dynamic – Checkout Flow]] | *(noch kein Playground-Eintrag — Lücke)* |
| 27 | [[27 SysML 2 IBD – Hybrid Vehicle Wiring]] | *(noch kein Playground-Eintrag — Lücke)* |
| 28 | [[28 SysML 2 PAR – Newton]] | `sysml2-newton-second-law-par` |
| 29 | [[29 UML Profil – AUTOSAR]] | `uml-profile-autosar` **und** `uml-profile-autosar-ecu` (Profil-Definition + Anwendung) |
| 30 | [[30 BPMN Process – Order Fulfillment]] | `bpmn-order-fulfillment` |
| 31 | [[31 BPMN Process – Sub-Process Loop]] | `bpmn-subprocess-loop` |
| 32 | [[32 BPMN Collaboration – Customer und Supplier]] | `bpmn-collaboration-customer-supplier` |
| 36 | [[36 BPMN Conversation – PdV Kommunikation]] | `bpmn-conversation-pdv` *(noch kein Playground-Eintrag — V3.2.3 neu)* |
| 38 | [[38 UML Profil – Exposed]] | `uml-profile-exposed` **und** `uml-profile-exposed-psm` (Profil-Definition + renderbares PSM-Beispiel) |
| 39 | [[39 ERM Martin – E-Commerce Schema]] | `erm-martin-ecommerce`, `erm-bachman-ecommerce`, `erm-chen-ecommerce`, `erm-idef1x-ecommerce` (alle vier Notationen seit 2026-07-11 im Playground) |
| 40 | [[40 UML Component – kUML Plugin API]] | *(noch kein Playground-Eintrag — Lücke; aus [[02 Projekte/kUML Präsentation Deutsch|Präsentation]] extrahiert, 2026-07-12)* |

### Playground-Keys ohne Vault-Anker

Diese Playground-Beispiele leben aktuell nur im Webseiten-Repo und haben kein Vault-Pendant. Beim nächsten größeren Beispiel-Refactoring entweder ein Vault-Beispiel ergänzen oder den Playground-Eintrag zugunsten eines vorhandenen Vault-Beispiels entfernen:

- `uml-profile-soaml` — SoaML-Stereotypen
- `uml-profile-spring` — Spring-Stereotypen
- `uml-profile-openapi` — OpenAPI-Stereotypen

> [!note] Sync-Workflow
> Wenn der ` ```kuml `-Block in einer Vault-Notiz angepasst wird, im Anschluss `playground-sources/<key>.kuml.kts` im Repo `kuml.dev` 1:1 nachziehen (Kotlin-Skript ohne Markdown-Drumherum), danach `npm run build:with-render` für die SVG-Neuerzeugung (nicht das normale `npm run build` — das überspringt den Render-Schritt). Tabellen-Änderungen hier ↔ `src/data/playground-examples.ts` immer parallel.

## Element-Tiefen-Audit (V3.2.18)

Audit-Ergebnis vom 2026-07-02: Alle 28 klassischen Diagrammtypen (UML 14, C4 6, SysML 2 8) sowie BPMN 2.0 (4 Typen) waren bereits **breitenmäßig** abgedeckt. Das Audit prüfte deshalb die **Element-Tiefe** — kommt jede Builder-Funktion/jeder optionale Parameter aus Handbuch + KDoc in mindestens einem Beispiel wirklich im Code vor (nicht nur in der Prosa der "Mögliche Erweiterungen"-Abschnitte)?

**Lücken-Matrix** (gefunden → geschlossen):

| Sprache | Element | Vorher | Geschlossen in |
|---|---|---|---|
| UML | `isAbstract`, `visibility`/`isStatic`/`isReadOnly`/`defaultValue` auf `attribute`, `parameter` in `operation`, `constraint`, `dependency`, `navigable = false` | nur in Prosa erwähnt, nie im Code | [[01 UML Klasse – Order Domain]] |
| UML | `exit`, `doActivity`, `choice`, `shallowHistory`, `deepHistory` (State Machine) | fehlte | [[18 UML State Machine – Order Lifecycle]] |
| UML | `asyncMessage`, `create`, `delete`, `opt`, `loop` (Sequence) | nur in Prosa erwähnt | [[19 UML Sequence – API Submit]] |
| SysML 2 | `isAbstract`, `specializesId` auf `partDef` (BDD-Spezialisierung) | fehlte | [[03 SysML 2 BDD – Hybrid Vehicle]] |
| C4 | `containerInstance`, `location`, `bidirectional` | fehlte | [[25 C4 Deployment – AWS Production]] |
| BPMN | `dataStore`/`dataObject`/`dataAssociation`, `callActivity`, `standardLoop`, `GatewayType.EVENT_BASED`/`PARALLEL`/`COMPLEX` | fehlte | [[30 BPMN Process – Order Fulfillment]] |
| BPMN | `multiInstance`, `subProcess(transactional = true)`, `subProcess(triggeredByEvent = true)` | fehlte | [[31 BPMN Process – Sub-Process Loop]] |
| BPMN | `lane`, `blackBoxPool` | fehlte | [[32 BPMN Collaboration – Customer und Supplier]] |
| UML | `objectNode`, `edge(objectFlow = true)` (Activity) | nur in Prosa erwähnt (»Mögliche Erweiterungen«) | [[41 UML Activity – Order Fulfillment (Objektfluss)]] (2026-07-21, nach Audit-Datum ergänzt) |

**Bewusst nicht geschlossen** (Out of scope laut Wellenspezifikation V3.2.18 bzw. objektiv nicht sinnvoll):

- `applyStereotypes(vararg names)` — reiner Namens-Alias für mehrfaches `stereotype(name)`, keine neue Semantik; nicht ergänzt, um Beispiele nicht künstlich aufzublähen.
- Handbuch-Referenz (`docs/handbook/modules/reference/pages/sysml2.adoc`) beschreibt teils eine **andere, nicht implementierte** SysML-2-API (`blockDef`/`valueProperty`/`composition`/`requirement`/`derives`/`satisfies` statt der tatsächlichen `partDef`/`attribute`/`part`/`requirementDef`/`derive`/`satisfy`). Das ist Doku-Drift, nicht Beispiel-Drift — Korrektur gehört zu V3.2.19 (Handbuch-Update), nicht zu diesem Audit.
- Journey- und Blueprint-Beispiele ([[33 Blueprint – PdV Mitglieder-Journey]], [[34 User Journey – PdV Mitglieder-Journey]]) wurden nicht auditiert — sie gehören nicht zu den vier in der Wellenspezifikation genannten Kernsprachen (UML/SysML 2/C4/BPMN).
- Neue Diagrammtypen und Handbuch-Einbettung sind laut Wellenspezifikation explizit out of scope (→ V3.2.19).
- `comment()` (UML-Notiz) für Aktivitätsdiagramme — **strukturell nicht möglich, nicht nur unbeworben.** Direkt am Renderer verifiziert (2026-07-21): `comment()` in einem `activityDiagram { … }`-Block lässt das gesamte Skript fehlschlagen (nicht nur „wird nicht angezeigt"). Laut `CommentDsl.kt` ist die DSL-Funktion bewusst nur für Klassen-, Sequenz- und State-Machine-Diagramme implementiert (einzige Typen mit Renderer-/Layout-Bridge-Anbindung für `UmlComment`/`UmlCommentLink`). Kein Vault-Beispiel für Activity+Comment anlegen, bis das im Kern erweitert wird.

**CI-Verifikation**: `./gradlew clean :kuml-tests:kuml-vault-examples-tests:test` — 44 Tests, 0 Failures (Stand 2026-07-02).

## Wozu diese Notizen?

1. **Smoke-Test** für das obsidian-kuml-Plugin nach Updates des Plugins oder von `kuml-web`
2. **Lebende Referenz** für die DSL-Syntax beim eigenen Modellieren
3. **Vorlagen** zum Kopieren und Anpassen in eigene Architektur-Notizen
4. **Vollständige Abdeckung** aller von kUML unterstützten Diagrammtypen — eine Notiz pro Typ

## Verwandte Dokumente

- [[02 Projekte/kUML V2.0]] — Projekt-Tracking inkl. Welleneintrag obsidian-kuml
- [[03 Bereiche/kUML/Übersicht]] — Bereich-Übersicht kUML
- [[03 Bereiche/kUML/DSL und Dateiformate]] — DSL-Konvention im Detail
