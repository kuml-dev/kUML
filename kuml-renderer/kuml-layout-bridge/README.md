# kuml-layout-bridge

Translates kUML models (UML and C4 hierarchies) with `kuml.layout.*` metadata into a
[`LayoutGraph`](../kuml-layout-api/src/main/kotlin/dev/kuml/layout/LayoutGraph.kt) from
`kuml-layout-api`. This closes the loop: DSL → Bridge → ELK-Adapter → `LayoutResult`.

Entry points: `UmlLayoutBridge.toLayoutGraph(diagram, sizeProvider)` and
`C4LayoutBridge.toLayoutGraph(diagram, model, sizeProvider)`.

Spec: Phase 1 — Layout-Bridge (Designentwurf), Sign-off 2026-05-31.
