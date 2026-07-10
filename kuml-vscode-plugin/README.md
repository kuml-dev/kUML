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
  `kuml` CLI. PNG output opens in your OS viewer; SVG output opens in the
  live-preview panel (see below).
- **Diagnostics + completion** via the `kuml-lsp` language server — parse and
  validation errors are pushed as you type (debounced), and completion
  (including resolve) is available for DSL builders and identifiers.
- **kUML: Open Live Preview** — a persistent webview panel that renders the
  active document as sanitized inline SVG and re-renders automatically on save
  and when you switch to another `*.kuml.kts` editor tab.
- **kUML: Restart Language Server** — stops and relaunches `kuml-lsp` without
  reloading the whole extension host window.

## Requirements

- The [kUML CLI](https://kuml.dev/#cli) (`kuml`) must be installed and
  reachable on your `PATH` (or pointed at via the `kuml.cliPath` setting).
  The render command and the live preview's CLI fallback both shell out to
  `kuml render`.
- The `kuml-lsp` language server binary must also be reachable — it's
  discovered the same way as `kuml`: an explicit path (`kuml.lspPath` setting
  or `KUML_LSP` env var) → PATH → Homebrew (`/opt/homebrew/bin`,
  `/usr/local/bin`) / `~/.local/bin` → a local Gradle build. If you're running
  from a clone of the `kUML` repo rather than an installed distribution, run
  `./gradlew :kuml-language-server:installDist` first so
  `kuml-language-server/build/install/kuml-lsp/bin/kuml-lsp` exists for the
  walk-up discovery to find.
- Syntax highlighting and snippets work without either binary installed.

## Live preview: dual render strategy

The **kUML: Open Live Preview** panel renders via two strategies, in order:

1. **`kuml serve` HTTP API** — if `kuml.serverUrl` is set (e.g.
   `http://127.0.0.1:8080`, from a locally running `kuml serve --port …`), the
   panel POSTs to `{serverUrl}/api/render` and inlines the returned SVG.
2. **CLI fallback** — if `kuml.serverUrl` is empty, or the HTTP call fails for
   any reason, the panel shells out to `kuml render` against a temp-file
   snapshot of the buffer (works for unsaved/dirty documents too).

Only SVG is inlined into the webview; PNG output from `kuml.renderToSvg`
still opens in your OS's default image viewer.

## Settings

| Setting                        | Default | Description                                                                                                          |
| ------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------- |
| `kuml.cliPath`                  | `kuml`  | Path to the `kuml` CLI executable. Override if installed in a non-standard location.                                 |
| `kuml.theme`                    | `kuml`  | Default `--theme` passed to `kuml render`. Any ThemeRegistry name works.                                             |
| `kuml.format`                   | `svg`   | Output format for `kuml.renderToSvg` (`svg` or `png`). SVG routes into the live-preview panel; PNG opens in your OS viewer. |
| `kuml.lspPath`                  | `""`    | Explicit path to the `kuml-lsp` launcher. Empty auto-detects it (PATH → Homebrew → `~/.local/bin` → local build).    |
| `kuml.serverUrl`                | `""`    | Base URL of a running `kuml serve` instance used by the live preview. Empty makes the preview shell out to `kuml render` instead. |
| `kuml.diagnostics.enable`       | `true`  | Enable push diagnostics from the language server.                                                                    |
| `kuml.diagnostics.debounceMs`   | `300`   | Debounce interval (ms) between an edit and the server re-validating the document.                                    |

## Out of scope

This extension is intentionally minimal — it gives you a good editor without
trying to be a full IDE. The following are deliberately left out for now:

- Hover, go-to-definition, rename, and code actions.
- Any custom render request on the LSP itself — the server stays
  render-agnostic; all rendering is a client-side concern.

For OCL validation and code generation, use the
[`dev.kuml` Gradle plugin](https://kuml.dev/#gradle) or the CLI directly.

## License

Apache-2.0 — same as the rest of the kUML toolchain.
