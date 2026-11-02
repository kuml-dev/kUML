# ADR-0016: SysML 2 packages, imports and views in diagrams

Status: accepted (partial). Option 2 is implemented in wave 2I-a (package diagram). Option 3 (views as evaluated
filters) stays open.

## Context
SysML 2 models are organised in packages, with imports and views. kUML SysML 2 diagrams project a flat
model by element id lists.

## Options
1. Keep flat ids; packages only as naming prefixes (current before wave 2I-a).
2. Package nodes as layout groups (like UML packages), imports as dashed dependency edges.
3. Views as named filters (expose + filter) evaluated into the existing id lists.

## Decision
Wave 2I-a implements **option 2** as a new diagram kind, `Sysml2PackageDiagram` (`packageDiagram("...") { ... }`):

- `Sysml2Package`, `Sysml2Import`, `Sysml2Viewpoint` and `Sysml2View` are plain data classes in `Sysml2Model`
  (new fields `packages`, `imports`, `viewpoints`, `views`, all defaulting to empty, so old `.kuml.json` stays readable).
  They are **not** `Sysml2Element`s: every exhaustive `when` over elements and definitions stays untouched.
- DSL: `pkg(name) { ... }` (Kotlin reserves `package`), `imports("P::*")`, `imports("P::**")`,
  `imports("P::X", public = true)` (Kotlin reserves `import`), `viewpoint(...) { stakeholder(..); concern(..) }`,
  `view(...) { satisfies(..); expose(..); filter(..) }`. Definition ids stay as they are; the qualified name of a
  member is derived from its package (`Package::Name`), so two definitions with the same name in different packages
  need an explicit `id`.
- Drawing: packages are unfilled frames with a name tab, three levels deep; from the fourth level a package is a flat
  card inside the level-3 frame (tab `C::D`, members listed). Imports are dashed arrows with an open head and the label
  `«import»`, `«import» *` or `«import» **` (prefixed `public ` for a public import); an import that cannot be an
  arrow (target not drawn or unknown, own package, ancestor or descendant) is an italic text line in its frame.
  Views and viewpoints are cards; a view points at its viewpoints with a dashed `«satisfy»` arrow.

Option 3 is **not** done: `expose` and `filter` are plain text on the view card. They are not evaluated and do not
change what a diagram draws.

## Not in wave 2I-a
- Evaluating `expose` / `filter` (option 3), wave 2I-b.
- Stakeholders and concerns as real model elements (they are text).
- Package frames in BDD / IBD.
- A LaTeX rendering of package diagrams (the renderer refuses with a clear message).
- An own OKF type for package diagrams (the knowledge-workspace export derives a custom type from the Kotlin name).

## Addendum wave 2I-b (analysis cases, calculations, perform / exhibit)
- Usages stay **fields of their definition**: `AnalysisCaseDefinition.calculations` (`CalcUsageRef`),
  `PartDefinition.performs` / `exhibits` (`BehaviorRef`). They are not model elements of their own, so no exhaustive `when`
  over elements changes. Only `Sysml2Definition` gains two subclasses (`CalculationDefinition`,
  `AnalysisCaseDefinition`); every existing exhaustive `when` over definitions was extended.
- Drawing: both kinds are BDD boxes with extra compartments (`BddBoxContent.compartments`, one source for layout bridge and
  renderer). Calcs of an analysis are composition edges, the subject a dashed `<<subject>>` edge; whatever is not drawn as an
  edge stays a compartment line. `perform` / `exhibit` are compartments, not edges.
- Open (design review): Alan Kay's objection that usages should become first-class elements stays open; a separate
  analysis view ("question view") is not done. Views are still not evaluated (option 3).

