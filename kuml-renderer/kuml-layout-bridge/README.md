# kuml-layout-bridge

Translates kUML model elements (UML and C4) into a `LayoutGraph` for `kuml-layout-api`.

Bridges the DSL output side to the layout engine input side:
`KumlDiagram` / `C4Diagram` + `kuml.layout.*` metadata → `LayoutGraph`.

## Entry Points

| Function | Description |
|---|---|
| `UmlLayoutBridge.toLayoutGraph(diagram)` | Converts a `KumlDiagram` (UML) to `LayoutGraph` |
| `C4LayoutBridge.toLayoutGraph(diagram, model)` | Converts a `C4Diagram` + `C4Model` to `LayoutGraph` |

Both functions respect `kuml.layout.*` metadata keys set via the DSL `layout { }` block
(column, row, `rightOf`, `below`, etc.).

## Position in the pipeline

```
diagram { }         ← kuml-core-dsl
    ↓
KumlDiagram
    ↓
UmlLayoutBridge     ← kuml-layout-bridge   (this module)
    ↓
LayoutGraph
    ↓
ElkLayoutEngine     ← kuml-layout-elk
    ↓
LayoutResult
    ↓
KumlSvgRenderer     ← kuml-io-svg
```

## License

Apache 2.0 — see [LICENSE](../../LICENSE)
