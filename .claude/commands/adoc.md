You are writing AsciiDoc documentation for the kUML project.

## CRITICAL: No Markdown
All documentation in the kUML repository is AsciiDoc (.adoc). Never create .md files for documentation.

This applies to:
- `README.adoc` (root and all modules)
- `CONTRIBUTING.adoc`
- `CHANGELOG.adoc`
- API documentation
- User manual (Antora, under `docs/`)

The ONLY .md file allowed in the repository is `CLAUDE.md`.

## Module README Template

```asciidoc
= kuml-[module-name]
:toc:
:toc-placement: preamble

Short one-sentence description of what this module does.

toc::[]

== Overview

2-3 sentences. What problem does this module solve? Where does it fit in the architecture?

== Installation

[source,kotlin]
----
dependencies {
    implementation("dev.kuml:kuml-[module]:1.0.0")
}
----

== Quick Start

[source,kotlin]
----
// Minimal example with named parameters
diagram(name = "MyDiagram") {
    classOf(name = "User") {
        visibility = PUBLIC
        attribute(name = "id", type = UUID, visibility = PRIVATE)
    }
}
----

== API Reference

=== Key Classes

`KumlXxx`:: Description.

=== Configuration

[source,kotlin]
----
// Configuration example
----

== Architecture

Brief description of internal design decisions.

== Related Modules

* xref:../kuml-yyy/README.adoc[kuml-yyy] — why it's related
```

## kuml:: Macro (for diagrams in docs)

```asciidoc
.Class diagram example
[kuml,name="user-model",format="svg",theme="plain"]
----
diagram(name = "UserModel") {
    classOf(name = "User") {
        visibility = PUBLIC
        attribute(name = "id", type = UUID, visibility = PRIVATE)
        attribute(name = "email", type = String, visibility = PRIVATE)
    }
}
----
```

## Antora User Manual Structure

Chapters are under `docs/modules/ROOT/pages/`:
- Part I (ch01-03): Getting Started
- Part II (ch04-07): DSL Reference
- Part III (ch08-10): Diagram Types
- Part IV (ch11-13): Transform & Generate
- Part V (ch14-19): Tools
- Part VI (ch20): Plugin Development
- Part VII (ch21): Examples

## Task
Write AsciiDoc documentation as requested. Always:
1. Use .adoc extension and AsciiDoc syntax (never Markdown)
2. Include named parameters in all code examples
3. Use `kuml::` macro for diagram examples
4. Follow the module README template for new modules
