---
title: SysML 2 REQ – Vehicle Requirements
date: 2026-06-11
tags:
  - kUML
  - beispiel
  - sysml2
  - requirements
  - traceability
status: aktiv
---

# SysML 2 Requirement Diagram — Vehicle Requirements

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> **Anforderungs-Modellierung** mit voller Traceability: fünf Fahrzeug-Requirements (Geschwindigkeit, Gewicht, Verbrauch, Emissionen, NOx), eine `Vehicle`-Konstruktion, ein Verify-Use-Case und die vier zentralen Beziehungs-Arten `satisfy`, `verify`, `derive`, `contains`. Genau das Diagramm, das in AUTOSAR-Projekten und Safety-Cases erwartet wird.

## Diagramm

```kuml
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "VehicleRequirements") {

    // ── Requirements ────────────────────────────────────────────────────
    val topSpeed = requirementDef(
        name = "TopSpeedRequirement",
        reqId = "R-001",
        text = "The vehicle shall reach at least 180 km/h on flat road",
        subject = "Vehicle",
    )
    val curbWeight = requirementDef(
        name = "CurbWeightRequirement",
        reqId = "R-002",
        text = "The vehicle curb weight shall not exceed 1500 kg",
        subject = "Vehicle",
    )
    val fuelEfficiency = requirementDef(
        name = "FuelEfficiencyRequirement",
        reqId = "R-003",
        text = "The vehicle shall consume less than 4 l/100km combined",
        subject = "Vehicle",
    )
    val emissions = requirementDef(
        name = "EmissionsRequirement",
        reqId = "R-004",
        text = "The vehicle shall comply with Euro 7 emissions standards",
        subject = "Vehicle",
    )
    val nox = requirementDef(
        name = "NOxRequirement",
        reqId = "R-005",
        text = "NOx emissions shall not exceed 30 mg/km",
        subject = "Vehicle",
    )

    // ── Satisfying design element ───────────────────────────────────────
    val vehicle = partDef(name = "Vehicle")

    // ── Verifying use case ──────────────────────────────────────────────
    val verifyTopSpeed = useCaseDef(name = "VerifyTopSpeed")

    // ── Requirement Diagram ─────────────────────────────────────────────
    reqDiagram(name = "Vehicle — top-level requirements") {
        // Nodes
        include(definition = topSpeed)
        include(definition = curbWeight)
        include(definition = fuelEfficiency)
        include(definition = emissions)
        include(definition = nox)
        include(definition = vehicle)
        include(definition = verifyTopSpeed)

        // Satisfy: Vehicle erfüllt R-001 / R-002 / R-003
        satisfy(source = vehicle, requirement = topSpeed)
        satisfy(source = vehicle, requirement = curbWeight)
        satisfy(source = vehicle, requirement = fuelEfficiency)

        // Verify: VerifyTopSpeed prüft R-001
        verify(source = verifyTopSpeed, requirement = topSpeed)

        // Derive: R-001 leitet sich aus R-003 ab
        // (Endgeschwindigkeit vs. Verbrauch ist ein klassischer Trade-off)
        derive(source = topSpeed, target = fuelEfficiency)

        // Contains: R-004 dekomponiert in R-005
        contains(parent = emissions, child = nox)
    }
}
```

## Die vier REQ-Beziehungen

| Beziehung | Bedeutung | Beispiel hier |
|---|---|---|
| **`satisfy(designElement, requirement)`** | „Diese Konstruktion erfüllt diese Anforderung" | `Vehicle` erfüllt `R-001` (Top Speed) |
| **`verify(useCase, requirement)`** | „Dieser Verifikations-Use-Case prüft diese Anforderung" | `VerifyTopSpeed` prüft `R-001` |
| **`derive(child, parent)`** | „Diese Anforderung ergibt sich logisch aus jener" | `R-001` (Top Speed) leitet sich aus `R-003` (Fuel) ab |
| **`contains(parent, child)`** | „Diese Anforderung dekomponiert in eine Unter-Anforderung" | `R-004` (Emissions) enthält `R-005` (NOx) |

## Anforderungs-Felder

| Feld | Bedeutung | Beispiel |
|---|---|---|
| `reqId` | Stabile externe ID (z. B. aus DOORS/Jira) | `"R-001"` |
| `name` | Kurzer Bezeichner für Diagramm | `"TopSpeedRequirement"` |
| `text` | Vollständige Anforderungs-Aussage | `"The vehicle shall reach at least 180 km/h…"` |
| `subject` | Das System/die Komponente, auf die sich die Anforderung bezieht | `"Vehicle"` |

## Warum REQ-Diagramme strategisch wichtig sind

In sicherheitskritischen Domänen (Automotive, Medizintechnik, Luftfahrt) ist **Traceability** ein regulatorisches Muss:

1. **ISO 26262 (Automotive)** verlangt vollständige Verfolgung von Safety Goals → System Requirements → Software Requirements → Code → Tests
2. **DO-178C (Avionik)**: jedes Code-Modul muss auf eine Anforderung zurückführbar sein
3. **AUTOSAR**: System-Architektur und Anforderungen werden formal verknüpft

Mit kUMLs `satisfy` und `verify` werden diese Verknüpfungen **maschinenlesbar** — was Audits und Coverage-Analysen automatisierbar macht. Genau einer der zentralen kUML-Marketing-Anker, siehe [[03 Bereiche/kUML/Übersicht]] → AUTOSAR-Priorität.

## Mögliche Erweiterungen

- **Constraint-Anbindung**: Anforderungen mit `parDiagram` koppeln, sodass `topSpeed >= 180` formal als Constraint-Expression vorliegt
- **Test-Cases**: jeder Verify-UseCase könnte selbst einen ACT haben, der den Test-Ablauf modelliert
- **Subject-Edge**: zukünftig automatische Verknüpfung Requirement ↔ Subject anhand des `subject`-Felds (V2.x)

## Verwandte Beispiele

- [[03 SysML 2 BDD – Hybrid Vehicle]] — die zu modellierende Konstruktion ohne Anforderungs-Layer
- [[07 SysML 2 ACT – Order Processing]] — wie ein Verify-UseCase prozessual aussehen würde
- [[05 SysML 2 UC – Library System]] — die Use-Case-Form, die hier als Verify-Vehicle verwendet wird

## Verwandte Vault-Notizen

- [[03 Bereiche/kUML/Übersicht#AUTOSAR Marketing-Priorität]]
- [[02 Projekte/kUML V2.0#SysML-2-Diagrammtyp-Serie ist KOMPLETT (8/8)]]
