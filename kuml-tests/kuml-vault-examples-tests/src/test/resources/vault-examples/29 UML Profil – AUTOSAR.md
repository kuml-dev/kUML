---
title: UML Profildiagramm – AUTOSAR Profile
date: 2026-06-15
tags:
  - kUML
  - beispiel
  - uml
  - profil
  - autosar
  - automotive
status: aktiv
---

# UML Profildiagramm — AUTOSAR Profile

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Das **AUTOSAR-UML-Profil** in kUML bringt die AUTOSAR-Softwarearchitektur-Konzepte als First-Class-Stereotypen in UML. Damit lassen sich `«AtomicSoftwareComponent»`, `«SenderReceiverInterface»` und `«Runnable»` typsicher im Klassendiagramm modellieren — ohne externen Profil-Editor und ohne proprietäres Plugin. Das Profil ist ein Built-in von kUML (`dev.kuml.profiles.autosar`).

## Profil-Diagramm

```kuml
profileDiagram(name = "AUTOSAR Adaptive Platform Profile") {
    val classMC = metaclass(name = "Class")
    val interfaceMC = metaclass(name = "Interface")
    val portMC = metaclass(name = "Port")
    val operationMC = metaclass(name = "Operation")

    val swComponent = stereotype(name = "SoftwareComponent", metaclasses = listOf("Class")) {
        tag(name = "kind", type = "String")
        tag(name = "category", type = "String")
    }

    val atomicSwc = stereotype(name = "AtomicSoftwareComponent", metaclasses = listOf("Class")) {
        tag(name = "kind", type = "String")
        tag(name = "memoryMappingEnabled", type = "Boolean")
    }

    val senderReceiverIf = stereotype(name = "SenderReceiverInterface", metaclasses = listOf("Interface")) {
        tag(name = "dataElement", type = "String")
        tag(name = "category", type = "String")
    }

    val clientServerIf = stereotype(name = "ClientServerInterface", metaclasses = listOf("Interface")) {
        tag(name = "category", type = "String")
    }

    val providedPort = stereotype(name = "ProvidedPort", metaclasses = listOf("Port")) {
        tag(name = "portInterface", type = "String")
    }

    val requiredPort = stereotype(name = "RequiredPort", metaclasses = listOf("Port")) {
        tag(name = "portInterface", type = "String")
    }

    val runnable = stereotype(name = "Runnable", metaclasses = listOf("Operation")) {
        tag(name = "period", type = "Integer")
        tag(name = "activationReason", type = "String")
    }

    extension(stereotype = swComponent, metaclass = classMC)
    extension(stereotype = atomicSwc, metaclass = classMC)
    extension(stereotype = senderReceiverIf, metaclass = interfaceMC)
    extension(stereotype = clientServerIf, metaclass = interfaceMC)
    extension(stereotype = providedPort, metaclass = portMC)
    extension(stereotype = requiredPort, metaclass = portMC)
    extension(stereotype = runnable, metaclass = operationMC)
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `metaclass(name = "Class")` | Verweis auf eine UML-Metaklasse. AUTOSAR erweitert `Class`, `Interface`, `Port` und `Operation`. |
| `stereotype(name = "AtomicSoftwareComponent", metaclasses = listOf("Class")) { … }` | Deklariert einen AUTOSAR-Stereotyp. `metaclasses` bestimmt, welche UML-Elemente ihn tragen dürfen. |
| `tag(name = "kind", type = "String")` | Tagged-Value — zusätzliche Properties, die der Stereotyp mitbringt (z. B. SWC-Kategorie, Zykluszeit). |
| `extension(stereotype = atomicSwc, metaclass = classMC)` | `«extension»`-Dependency zwischen dem Stereotyp und der UML-Metaklasse. |

## Anwenden des Profils — ECU-Beispiel

Ein realistisches Automotive-Beispiel: zwei SW-Komponenten (`SpeedSensor` und `BrakeController`) kommunizieren über ein Sender-Receiver-Interface. Das `«Runnable»`-Stereotyp markiert den zyklischen Ausführungspunkt.

```kuml
classDiagram(name = "Braking ECU – SW Component Architecture") {
    val sensor = classOf(name = "SpeedSensorSwc") {
        stereotypes += "AtomicSoftwareComponent"
        attribute(name = "rawSpeedValue", type = "Float")
        operation(name = "readSensor") {
            stereotypes += "Runnable"
        }
    }

    val controller = classOf(name = "BrakeControllerSwc") {
        stereotypes += "AtomicSoftwareComponent"
        attribute(name = "brakePressure", type = "Float")
        operation(name = "controlBrake") {
            stereotypes += "Runnable"
        }
    }

    val speedIf = interfaceOf(name = "SpeedSignalIf") {
        stereotypes += "SenderReceiverInterface"
    }

    realization(implementing = sensor, iface = speedIf)
    dependency(client = controller, supplier = speedIf)
}
```

## Built-in vs. Custom Profile

| | Built-in `dev.kuml.profiles.autosar` | Eigenes Custom-Profil |
|---|---|---|
| **Bezug** | Teil von kUML, kein Setup nötig | Per JAR, Maven-Koordinaten oder Inline-DSL |
| **Stereotypen** | `SoftwareComponent`, `AtomicSoftwareComponent`, `Port`, `PortInterface`, `SenderReceiverInterface`, `ClientServerInterface`, `Runnable`, … | Frei definierbar |
| **XMI-Export** | Optional via `kuml profile export autosar` | Identisch |
| **Anwenden** | `stereotypes += "AtomicSoftwareComponent"` (Display-Label) | `applyProfile(myAutosarExtension)` — nach `ProfileRegistry`-Registrierung |

## Mögliche Erweiterungen

- **Composition SW Component**: `stereotype(name = "CompositionSoftwareComponent", metaclasses = listOf("Class"))` — schachtelt `AtomicSoftwareComponent`s
- **InitEvent / DataReceivedEvent**: weitere `activationReason`-Werte für nicht-zyklische Runnables
- **Klassendiagramm als Kompositionsstruktur**: `compositeStructureDiagram` + AUTOSAR-Profil für Port-Verkabelung auf ECU-Ebene
- **Deployment**: `deploymentDiagram` + AUTOSAR-Profil, `«device»` = ECU, `«artifact»` = SW-Cluster-Image

## Verwandte Beispiele

- [[15 UML Profil – Java EE Profile]] — identischer Mechanismus, Web-Stack-Domäne
- [[12 UML Component – Order Architecture]] — Komponenten ohne Profil-Anreicherung
- [[13 UML Composite Structure – Order Internals]] — Ports und Konnektoren ohne Stereotypen
- [[03 SysML 2 BDD – Hybrid Vehicle]] — System-Sicht für Automotive (eigene Sprache, kein Profil)
- [[03 Bereiche/kUML/Profile System]] — technische Referenz aller fünf Built-in-Profile
- [[03 Bereiche/kUML/AUTOSAR Marketing-Priorität]] — warum AUTOSAR das Marketing-First-Profil ist
