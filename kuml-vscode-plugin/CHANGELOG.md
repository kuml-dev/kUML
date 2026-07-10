# Change Log

All notable changes to the kUML VS Code extension are documented in this file.

## [0.2.0] — 2026-07-10

Wave 5: thin LSP client + live-preview webview.

### Added
- LSP client wired to `kuml-lsp` (the `kuml-language-server` module) via
  `vscode-languageclient`: push diagnostics and completion (with resolve) now
  appear directly in the editor for `*.kuml.kts` files, over stdio.
- Launcher discovery for `kuml-lsp` mirrors the `kuml` CLI's resolution order:
  `KUML_LSP` env var → `kuml.lspPath` setting → PATH → Homebrew/common
  locations → local Gradle `installDist` build (walked up from the workspace).
- Command **kUML: Open Live Preview** — a persistent webview panel that
  renders the active `*.kuml.kts` document as sanitized inline SVG, using a
  dual strategy: `kuml.serverUrl` (`kuml serve`'s `/api/render` HTTP endpoint)
  first, falling back to the `kuml` CLI subprocess. Re-renders on save and on
  active-editor change.
- Command **kUML: Restart Language Server**.
- Configuration: `kuml.lspPath`, `kuml.serverUrl`, `kuml.diagnostics.enable`,
  `kuml.diagnostics.debounceMs`.
- 128×128 PNG marketplace icon (`icons/kuml-marketplace.png`), required by
  `vsce` (SVG icons are rejected).

### Notes
- `kuml.renderToSvg` now delegates SVG output to the live-preview panel; PNG
  output still opens in the OS's default viewer.
- The LSP server stays render-agnostic (diagnostics + completion only) — the
  live preview's rendering logic lives entirely in the client.

## [0.1.0] — 2026-06-06

Initial release. V1.1.11 of the kUML toolchain.

### Added
- TextMate grammar (`source.kuml`) covering the kUML DSL on top of Kotlin
  script basics (strings, numbers, comments, types, identifiers).
- File-type registration for `*.kuml.kts` with a custom file icon
  (light + dark variants).
- Snippets: `diagram`, `umlModel`, `classOf`, `interfaceOf`, `enumOf`,
  `c4Model`, `association`, `generalization`, `realization`, `stateMachine`,
  `applyProfile`.
- Command **kUML: Render to SVG** — invokes the `kuml` CLI on the active
  document and opens the resulting SVG/PNG.
- Configuration: `kuml.cliPath`, `kuml.theme`, `kuml.format`.
