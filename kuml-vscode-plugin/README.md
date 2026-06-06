# kUML for Visual Studio Code

First-class editor support for [kUML](https://kuml.dev) diagram scripts
(`*.kuml.kts`) in Visual Studio Code.

## Features

- **Syntax highlighting** for the kUML DSL on top of Kotlin script syntax —
  diagram entry points (`classDiagram`, `umlModel`, `c4Model`, …) and DSL
  builders (`classOf`, `interfaceOf`, `enumOf`, `association`, …) are
  highlighted as first-class language constructs.
- **Snippets** for the common diagram shapes: `diagram`, `umlModel`, `classOf`,
  `interfaceOf`, `enumOf`, `c4Model`, `association`, `stateMachine`,
  `generalization`, `realization`, `applyProfile`.
- **File icon** for `*.kuml.kts` in the explorer and editor tabs.
- **One-click render** via the **kUML: Render to SVG** command — invokes the
  `kuml` CLI and opens the resulting SVG in a preview tab.

## Requirements

- The [kUML CLI](https://kuml.dev/#cli) must be installed and reachable on your
  `PATH` (or pointed at via the `kuml.cliPath` setting). The render command
  shells out to `kuml render`; the syntax-highlighting and snippets features
  work without the CLI.

## Settings

| Setting          | Default | Description                                                                                |
| ---------------- | ------- | ------------------------------------------------------------------------------------------ |
| `kuml.cliPath`   | `kuml`  | Path to the `kuml` CLI executable. Override if installed in a non-standard location.       |
| `kuml.theme`     | `kuml`  | Default `--theme` passed to `kuml render`. Any ThemeRegistry name works.                   |
| `kuml.format`    | `svg`   | Output format (`svg` or `png`). SVG opens in a preview tab; PNG opens in your OS viewer.   |

## Out of scope

This extension is intentionally minimal — it gives you a good editor without
trying to be an IDE. The following are deliberately left out of v0.1.x and
will land later (or in tooling that's a better fit):

- Language Server Protocol (LSP) integration — V2
- Inline kUML/OCL validation diagnostics — V2
- Code completion beyond snippets — V2
- A live-preview panel that re-renders on save — V2

For OCL validation and code generation, use the
[`dev.kuml` Gradle plugin](https://kuml.dev/#gradle) or the CLI directly.

## License

Apache-2.0 — same as the rest of the kUML toolchain.
