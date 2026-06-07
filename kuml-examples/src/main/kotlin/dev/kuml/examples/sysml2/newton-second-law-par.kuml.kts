@file:Suppress("unused")

import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Newton's Second Law — SysML 2 Parametric Diagram example (V2.0.12 MVP,
 * schließende achte Welle der SysML-2-Diagramm-Typ-Serie).
 *
 * Illustriert die V2.0.12-Oberfläche end-to-end mit dem klassischen
 * physikalischen Beispiel `F = m·a`:
 *  - Drei **AttributeDefinitions** `Mass`, `Acceleration`, `Force` als
 *    Wert-Typen.
 *  - Eine **ConstraintDefinition** `NewtonsLaw` mit Expression-Body
 *    `"F = m * a"` und drei typisierten Parameter-Pins (`F` als Out, `m` und
 *    `a` als In).
 *  - Eine **PartDefinition** `Vehicle` mit drei Attribut-Usages
 *    (`mass : Mass`, `acceleration : Acceleration`, `force : Force`).
 *  - Drei **BindingConnectorUsages**, die die Parameter-Pins des Constraints
 *    an die Vehicle-Attribute koppeln:
 *    - `NewtonsLaw::F ↔ Vehicle::force`
 *    - `NewtonsLaw::m ↔ Vehicle::mass`
 *    - `NewtonsLaw::a ↔ Vehicle::acceleration`
 *
 * Architektur-Hinweis: Bindings leben wie IBD-Connections, STM-Transitions
 * und ACT-Flows auf dem Modell (`bind(...)` registriert in
 * `Sysml2Model.usages`), nicht auf dem Diagramm. Der Layout-Bridge nimmt
 * Bindings automatisch auf, wenn beide Endpunkte per Longest-Prefix-Match auf
 * sichtbare Elemente auflösen — exakt dieselbe Pattern-A-Konvention wie IBD /
 * STM / ACT.
 *
 * Out of V2.0.12 scope (siehe Wave-Plan):
 *  - Composite Constraints (ein Constraint enthält einen anderen via
 *    ConstraintUsage)
 *  - Parameter-Pin-Endpunkt-Anchoring (Bindings docken am Pin statt am
 *    Box-Mittelpunkt an)
 *  - Equation-Rendering via MathJax / KaTeX (heute monospaced Raw-Text)
 *  - Typed Constraint Expression AST (heute Raw-String)
 *  - Solver-Hookup (parametrische Werte-Propagation gehört zum
 *    Behaviour-Runtime-Wave)
 *  - PNG-Export für SysML 2 PAR
 */
sysml2Model("NewtonModel") {

    // ── AttributeDefinitions (Wert-Typen) ─────────────────────────────────
    attributeDef("Mass")
    attributeDef("Acceleration")
    attributeDef("Force")

    // ── ConstraintDefinition: F = m·a ─────────────────────────────────────
    val newton =
        constraintDef(
            name = "NewtonsLaw",
            expression = "F = m * a",
            parameters =
                listOf(
                    ConstraintParameter("F", "Force", ConstraintParameterDirection.Out),
                    ConstraintParameter("m", "Mass", ConstraintParameterDirection.In),
                    ConstraintParameter("a", "Acceleration", ConstraintParameterDirection.In),
                ),
        )

    // ── PartDefinition Vehicle mit Attribut-Usages ────────────────────────
    val vehicle =
        partDef("Vehicle") {
            attribute("mass", "Mass")
            attribute("acceleration", "Acceleration")
            attribute("force", "Force")
        }

    // ── Bindings: Constraint-Pins ↔ Vehicle-Attribute ─────────────────────
    bind(
        name = "F_to_force",
        source = "NewtonsLaw::F",
        target = "Vehicle::force",
    )
    bind(
        name = "m_to_mass",
        source = "NewtonsLaw::m",
        target = "Vehicle::mass",
    )
    bind(
        name = "a_to_acceleration",
        source = "NewtonsLaw::a",
        target = "Vehicle::acceleration",
    )

    // ── Parametric Diagram ────────────────────────────────────────────────
    parDiagram("Newton — F = m·a applied to Vehicle") {
        include(newton)
        include(vehicle)
    }
}
