# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Features

- Markdown integration: `MarkdownProcessor` library + `kuml markdown` CLI
  subcommand with `inline` / `linked-svg` / `linked-png` modes
- Pandoc Lua filter (`tools/pandoc/kuml.lua`) for HTML / PDF rendering
- Maven Central publishing infrastructure (vanniktech plugin) for all
  library modules; applications (`kuml-cli`, `kuml-mcp`, `kuml-llm-bench`)
  remain unpublished
- GitHub Release workflow with `git-cliff` changelog generation
- Homebrew formula template (`Formula/kuml.rb`)

### Documentation

- Getting-Started guide (`docs/getting-started.adoc`)
- Diagram-Type Reference (`docs/diagram-types.adoc`)
- LLM benchmark guide + reproducible mock report
  (`docs/benchmark/README.md`, `docs/benchmark/benchmark-report-mock.md`)
- Release & distribution walkthrough (`docs/release.md`)

### Tests

- `DynamicDiagramBuilderTest` closes the C4 DSL coverage gap

> Once a `v*.*.*` tag is pushed, the release workflow re-generates this file
> automatically from the Conventional-Commit history via `cliff.toml`.
