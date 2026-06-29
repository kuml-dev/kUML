---
title: "AUTOSAR Classic – SW-Komponenten Komposition"
date: 2026-06-26
tags:
  - kuml
  - autosar
  - automotive
  - uml-component
  - arxml
status: aktiv
---

# AUTOSAR Classic – SW-Komponenten Komposition

> [!info] Dieses Beispiel modelliert eine AUTOSAR Classic Sensor-Aktor-Komposition mit drei Software-Komponenten (SWCs), ihren Ports und zwei Kommunikationsinterfaces.

## kUML-Quelltext

```kuml
import dev.kuml.core.dsl.layout.*   // aktiviert layout { col / row / … } in allen Buildern

componentDiagram("AUTOSAR Classic – Sensor/Brake/Diag") {

    // Grid-Layout: Treppenform — SS allein in Zeile 0 (col 0), BC+DA in Zeile 1
    // (col 1/2), Interfaces in Zeile 2. SpeedOut→SpeedIn benutzt so eine echte
    // U-Route mit vertikalem Korridor bei x≈26, der links von SS und BC verläuft
    // und keine Komponentenbox schneidet.
    layoutEngine = "kuml.grid"

    // ── Interfaces (Sender-Receiver und Client-Server) ─────────────────────
    val iSpeed = interfaceOf("ISpeed") {
        stereotypes += "ComInterface"
        operation("getSpeedKmh(): Float")
        layout { col = 0; row = 2 }
    }
    val iDiag = interfaceOf("IDiag") {
        stereotypes += "ComInterface"
        operation("reportFault(code: Int)")
        layout { col = 2; row = 2 }
    }

    // ── SpeedSensorSwc ─────────────────────────────────────────────────────
    val speedSensor = component("SpeedSensorSwc") {
        stereotypes += "SoftwareComponent"
        provides(iSpeed, name = "SpeedOut")
        // DiagIn bewusst weggelassen – wird im übergeordneten
        // Kompositionskontext (System Composition) verdrahtet
        layout { col = 0; row = 0 }
    }

    // ── BrakeControllerSwc ─────────────────────────────────────────────────
    val brakeCtrl = component("BrakeControllerSwc") {
        stereotypes += "SoftwareComponent"
        requires(iSpeed, name = "SpeedIn")
        provides(iDiag, name = "DiagOut")
        layout { col = 1; row = 1 }
    }

    // ── DiagActuatorSwc ────────────────────────────────────────────────────
    val diagActuator = component("DiagActuatorSwc") {
        stereotypes += "SoftwareComponent"
        requires(iDiag, name = "DiagIn")
        // DiagOut bewusst weggelassen – Rückkanal liegt außerhalb
        // dieser Kompositionssicht
        layout { col = 2; row = 1 }
    }

    // ── Verbindungen (azyklische Kette, keine Kreuzungen möglich) ───────────
    connect(speedSensor.port("SpeedOut"), brakeCtrl.port("SpeedIn"))
    connect(brakeCtrl.port("DiagOut"), diagActuator.port("DiagIn"))
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---------|-----------|
| `interfaceOf("ISpeed")` | Kommunikationsinterface (AUTOSAR: Sender-Receiver-Interface) |
| `interfaceOf("IDiag")` | Diagnose-Interface (AUTOSAR: Client-Server-Interface) |
| `component("SpeedSensorSwc")` | Software-Komponente mit AUTOSAR-Stereotyp |
| `provides(iface, name = "…")` | Angebotener Port (AUTOSAR: P-PORT-PROTOTYPE) |
| `requires(iface, name = "…")` | Benötigter Port (AUTOSAR: R-PORT-PROTOTYPE) |
| `connect(portA, portB)` | Assembly-Connector zwischen SWCs |
| `stereotypes += "SoftwareComponent"` | AUTOSAR-Profil-Stereotyp für Exportvorbereitung (fügt zur MutableList hinzu) |

## ARXML-Export

```bash
# Exportiert das Modell als AUTOSAR Classic ARXML (R22-11)
kuml export --format arxml 35_AUTOSAR_Classic_SW-Komponenten.kuml.kts -o model.arxml

# Reimportiert alle *.arxml-Dateien aus einem Verzeichnis und merged sie
kuml reverse --format arxml ./arxml-sources/ -o merged.kuml.kts
```

## Verwandte Beispiele

- [[12 UML Component – Order Architecture]] — Allgemeines UML-Komponentendiagramm ohne AUTOSAR-Profil
- [[29 UML Profil – AUTOSAR]] — AUTOSAR-Profil-Stereotypen im Detail
- [[09 C4 Landscape – Enterprise Banking]] — Systemlandschaft mit C4-Notation

## AUTOSAR-Hintergrund

AUTOSAR (AUTomotive Open System ARchitecture) definiert eine standardisierte Software-Architektur für Steuergeräte (ECUs). Die Kernkonzepte:

- **SWC (Software Component)**: Wiederverwendbare Softwareeinheit mit definierten Ports
- **P-PORT**: Provided Port — bietet ein Interface an
- **R-PORT**: Required Port — benötigt ein Interface
- **ARXML**: AUTOSAR XML-Format für den Austausch von Modellen zwischen Tools
- **Runnable**: Ausführbare Einheit innerhalb einer SWC-Internal-Behavior

kUML unterstützt AUTOSAR Classic R19-11 bis R23-11 und Adaptive Platform-Manifeste.
