---
title: SysML 2 PAR – Newton's Second Law
date: 2026-06-14
tags:
  - kUML
  - beispiel
  - sysml2
  - par
status: aktiv
---

# SysML 2 Parametric Diagram — Newton's Second Law

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> Ein **SysML 2 PAR** (Parametric Diagram) modelliert *Constraints* zwischen Attributen — typischerweise physikalische oder mathematische Gleichungen. Hier: Newtons zweites Gesetz `F = m · a`, gebunden an die Attribute eines `Vehicle`-Blocks. Bindings koppeln die Constraint-Parameter (`F`, `m`, `a`) an konkrete Vehicle-Attribute.

## Diagramm

```kuml
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "NewtonModel") {
    attributeDef(name = "Mass")
    attributeDef(name = "Acceleration")
    attributeDef(name = "Force")

    val newton = constraintDef(
        name = "NewtonsLaw",
        expression = "F = m * a",
        parameters = listOf(
            ConstraintParameter(name = "F", typeId = "Force",        direction = ConstraintParameterDirection.Out),
            ConstraintParameter(name = "m", typeId = "Mass",         direction = ConstraintParameterDirection.In),
            ConstraintParameter(name = "a", typeId = "Acceleration", direction = ConstraintParameterDirection.In),
        ),
    )

    val vehicle = partDef(name = "Vehicle") {
        attribute(name = "mass",         typeId = "Mass")
        attribute(name = "acceleration", typeId = "Acceleration")
        attribute(name = "force",        typeId = "Force")
    }

    bind(name = "F_to_force",         source = "NewtonsLaw::F", target = "Vehicle::force")
    bind(name = "m_to_mass",          source = "NewtonsLaw::m", target = "Vehicle::mass")
    bind(name = "a_to_acceleration",  source = "NewtonsLaw::a", target = "Vehicle::acceleration")

    parDiagram(name = "Newton — F = m·a applied to Vehicle") {
        include(definition = newton)
        include(definition = vehicle)
    }
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `attributeDef(name = "Mass")` | Werttyp — wird als `typeId` an Attributen referenziert. |
| `constraintDef(name = …, expression = …, parameters = listOf(…))` | Constraint-Definition mit Ausdruck und typisierten Parameter-Pins. |
| `ConstraintParameter(name, typeName, direction)` | Pin mit Richtung (`In`, `Out`, `InOut`). |
| `partDef(name = "Vehicle") { attribute(name = "mass", typeId = "Mass") }` | Block mit typisierten Attribut-Usages. |
| `bind(name = …, source = "Constraint::Pin", target = "Part::Attr")` | Binding zwischen Constraint-Pin und Part-Attribut. Endpoints per Longest-Prefix-Match. |
| `parDiagram(name = …) { include(…) }` | Erzeugt das Parametric-Diagramm. |

## Wo Parametric leuchtet

- **Physikalische Modelle**: Newtonsche Mechanik, Thermodynamik, Elektrotechnik
- **Anforderungs-Verifikation**: erfüllt das System bei diesen Eingangsparametern die Anforderungen?
- **Engineering Trade-offs**: was-wäre-wenn-Analysen mit Solver-Anbindung

## Out of MVP-Scope

- Composite Constraints (Constraint enthält Constraint)
- Pin-Endpunkt-Anchoring (Bindings docken am Pin statt Box-Mittelpunkt)
- Equation-Rendering via KaTeX (heute monospaced)
- Typed Constraint Expression AST (heute Raw-String)
- Solver-Hookup (parametrische Wertepropagation — Behaviour-Runtime-Wave)

## Verwandte Beispiele

- [[03 SysML 2 BDD – Hybrid Vehicle]] — Strukturelle Definitionssicht
- [[27 SysML 2 IBD – Hybrid Vehicle Wiring]] — Connections statt Constraints
- [[08 SysML 2 REQ – Vehicle Requirements]] — Anforderungen, die durch Bindings verifiziert werden können
