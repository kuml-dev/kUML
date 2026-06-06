# Change Log

All notable changes to the kUML VS Code extension are documented in this file.

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
