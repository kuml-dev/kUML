# kuml-layout-elk

ELK 0.11.0 adapter for kUML.

Implements `KumlLayoutEngine` from `kuml-layout-api` using
[Eclipse Layout Kernel](https://www.eclipse.org/elk/) — specifically the
`elk.layered` algorithm (Sugiyama / hierarchical layout).

## What it does

1. Translates a `LayoutGraph` (from `kuml-layout-bridge`) into an ELK graph
2. Runs `elk.layered` layout
3. Translates ELK node positions + edge bend-points back into `LayoutResult`

ELK types never leave this module — all public API surfaces are ELK-free.

## Dependency note

ELK 0.11.0's POM does not declare `org.eclipse.xtext:org.eclipse.xtext.xbase.lib`
as a runtime dependency, even though `LayeredMetaDataProvider` requires it.
This module adds it explicitly:

```kotlin
// build.gradle.kts
implementation(libs.xtext.xbase.lib)   // "2.43.0" — ELK 0.11.0 runtime fix
```

## GraalVM Native Image

Reflection configuration for ELK's service-loader mechanism will be added in
`kuml-packaging/kuml-native`. This module is JVM-only in V1.

## License

Apache 2.0 — see [LICENSE](../../LICENSE)
