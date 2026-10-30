---
title: SysML 2 IBD – Notizen an Port, Verbindung und Owner
date: 2026-10-30
tags:
  - kUML
  - beispiel
  - sysml2
  - ibd
  - notiz
status: aktiv
---

# SysML 2 IBD – Notizen an Port, Verbindung und Owner

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Drei Notizen an einem Part-Port, einer Verbindung und am Owner des IBD. (PAL-Welle 2A, P-COM)

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Hybrid") {
    val powerLine = connectionDef("PowerLine")
    val dcPort = portDef("DcPort")
    val battery = partDef("Battery") { port(name = "dcOut", typeId = dcPort.id) }
    val motor = partDef("ElectricMotor") { port(name = "dcIn", typeId = dcPort.id) }
    val hybrid = partDef("HybridVehicle") {
        part(name = "battery", typeId = battery.id)
        part(name = "electricMotor", typeId = motor.id)
        connect(
            name = "batteryToMotor",
            typeId = powerLine.id,
            sourceEndId = "HybridVehicle::battery::dcOut",
            targetEndId = "HybridVehicle::electricMotor::dcIn",
        )
    }
    comment(text = "DC, 400 V", "Battery::dcOut", id = "np")
    comment(text = "Fused at 200 A", "HybridVehicle::batteryToMotor", id = "nc")
    comment(text = "Owner note", "HybridVehicle", id = "no")
    ibd("Hybrid wiring", owner = hybrid)
}
```
