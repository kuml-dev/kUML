# kuml-io-svg KMP Compatibility Audit (V3.2.8)

## Summary
Audited all 89 Kotlin source files in `kuml-io-svg/src/main/kotlin` for KMP-blocking
`java.*` / `javax.*` usage. The **SVG string-building renderer core is essentially pure
Kotlin** — the only genuine blockers fall into two well-contained categories, both
already isolatable:

1. **Number formatting** — 44 call sites of the JVM-only `String.format` extension in the
   uniform shape `"%.2f".format(Locale.ROOT, v)` (42× spelled `java.util.Locale.ROOT`, 2×
   via an `import java.util.Locale`). These format a `Float` coordinate to 2 decimal places
   with a locale-independent decimal point for deterministic SVG output. This is a **single
   cross-cutting concern**, not 44 independent problems.
2. **File / path IO** — `KumlSvgRenderer.kt` exposes multiple public `toSvgFile(out: Path): File`
   overloads that call `out.toFile()`, `file.parentFile?.mkdirs()`, and
   `file.writeText(svg, Charsets.UTF_8)` (`import java.io.File`, `import java.nio.file.Path`,
   plus 3 fully-qualified `java.nio.file.Path` / `java.io.File` signatures at the bottom of
   the file). **These are exactly the PNG/file-write paths the task expects to stay JVM-only**
   — they belong in `jvmMain`, the pure `toSvg(...): String` renderers do not depend on them.

One further minor item: `UmlComponentSvg.kt:552` uses `System.err.println(...)` for a
warning. `System` is JVM-only; this is a trivial swap to a common logging shim or plain
`println`/expect-fun.

Explicitly **not found anywhere** in the module: `java.time`, `java.math`
(BigDecimal/BigInteger), `java.util.UUID`, `java.util.regex.Pattern`, `ServiceLoader`,
`Class.forName` / `::class.java`, `Thread` / `kotlin.concurrent.thread` / `ThreadLocal`,
`synchronized` blocks, `@Jvm*` annotations, `java.util.concurrent.*` atomics, or any
`javax.*`. All `StringBuilder` usage (28 sites) is Kotlin stdlib and already multiplatform.
`Charsets.UTF_8` appears only inside the `toSvgFile` file-write paths (jvmMain-bound).

**Conclusion on feasibility:** the renderer core itself is KMP-clean once the number-format
helper is centralized and the `toSvgFile` overloads are moved to a JVM source set. **BUT the
actual commonMain/jvmMain split cannot be completed in this wave** — see the dependency-chain
blocker below.

## The real blocker: upstream dependency chain is not yet multiplatform
`kuml-io-svg`'s `build.gradle.kts` re-exports **11 modules via `api(...)`**. A `kotlin.jvm`
`api()` dependency cannot be consumed by non-JVM targets of a multiplatform module, so
`kuml-io-svg` cannot declare a real `commonMain` (with js/wasmJs targets) until **every**
`api()`-exposed upstream is itself multiplatform. Current status:

| Upstream (`api`) dependency | Plugin today | KMP-ready? |
|---|---|---|
| `kuml-renderer:kuml-layout-api` | `kotlin.jvm` | ❌ blocks |
| `kuml-renderer:kuml-layout-bridge` | `kotlin.jvm` | ❌ blocks |
| `kuml-renderer:kuml-themes-core` | `kotlin.jvm` | ❌ blocks |
| `kuml-metamodel:kuml-metamodel-uml` | `kotlin.multiplatform` | ✅ |
| `kuml-metamodel:kuml-metamodel-c4` | `kotlin.multiplatform` | ✅ |
| `kuml-metamodel:kuml-metamodel-sysml2` | `kotlin.jvm` | ❌ blocks |
| `kuml-metamodel:kuml-metamodel-bpmn` | `kotlin.jvm` | ❌ blocks |
| `kuml-metamodel:kuml-metamodel-blueprint` | `kotlin.jvm` | ❌ blocks |
| `kuml-io:kuml-render-smil` | `kotlin.jvm` | ❌ blocks |
| `kuml-runtime:kuml-runtime-core` | `kotlin.jvm` | ❌ blocks |
| `kuml-core:kuml-core-model` (`implementation`) | `kotlin.multiplatform` | ✅ |

7 of the 11 `api()` upstreams (and the layout/renderer/runtime/smil chain in particular) are
still JVM-only. Until at least the layout-api, layout-bridge, themes-core, the three
JVM metamodels, render-smil and runtime-core are KMP-migrated in their own waves,
`kuml-io-svg` **cannot** flip to `kotlin.multiplatform` with additional targets.

## java.* Usage Inventory

| Category | Files (count) | Import / usage | jvmMain vs commonMain | KMP replacement | Effort |
|---|---|---|---|---|---|
| Number format `"%.2f".format(Locale.ROOT, v)` | 44 call sites across 56 files (2-decimal float→string) | JVM `String.format` ext + `java.util.Locale` (explicit import in `UmlComponentContracts.kt`, `sysml2/edge/Sysml2EdgeRenderer.kt`; otherwise fully-qualified `java.util.Locale.ROOT`) | commonMain (core renderer) | Centralize into one helper `fun fmt2(v: Float): String` in commonMain. Implement locale-independent 2-decimal formatting in pure Kotlin (round to 2 dp: `(v*100).roundToInt()/100.0` then build the string manually) OR back it with a tiny `expect fun formatFixed2(v: Double): String` / `actual` on JVM using `String.format`. Deterministic decimal point guaranteed either way. Replace all 44 sites with `fmt2(...)`. | medium (mechanical, 44 sites, but one concept) |
| File write `toSvgFile(out: Path): File` overloads | `KumlSvgRenderer.kt` (12 overloads) | `import java.io.File`, `import java.nio.file.Path`; `out.toFile()`, `parentFile?.mkdirs()`, `writeText(svg, Charsets.UTF_8)`; 3 fully-qualified sigs | **jvmMain** (expected JVM-only, per task) | Keep on JVM. The `toSvg(...): String` producers stay in commonMain; the `toSvgFile(...)` convenience wrappers become JVM-only extensions/overloads in `jvmMain` (or move to a `kuml-io-svg-jvm` facet). No common replacement needed — file IO is intentionally JVM-side. | low (relocate, no logic change) |
| Warning log | `uml/UmlComponentSvg.kt:552` | `System.err.println(...)` | commonMain | Swap to plain `println(...)` or a common `expect fun warn(msg: String)`; `System` is JVM-only. | low |

## V3.2.8 Decision: audit-only this wave (full split deferred)
Following the exact precedent of the V3.2.6 kuml-core-dsl handling (which kept the module
JVM-only and deferred true `commonMain` extraction until upstreams migrate), the **full
commonMain/jvmMain split for `kuml-io-svg` is NOT feasible in this wave** because 7 of the
11 `api()`-exposed upstream modules are still `kotlin.jvm`. Attempting the split now would
force `kuml-io-svg` to remain a jvm()-only multiplatform module anyway, giving no js/wasmJs
reach while adding source-set churn with zero payoff.

**What this wave delivers:** this audit document, plus (optionally, low-risk) the
*platform-agnostic refactors that are safe to do while still on `kotlin.jvm`*:

## Recommended incremental plan
1. **This wave (safe on `kotlin.jvm`, no target change):**
   - Introduce a single `internal fun fmt2(v: Float): String` (e.g. in a new
     `SvgNumberFormat.kt` or extend `UmlFormatHelpers.kt`) that produces the exact same
     locale-independent 2-decimal output as `"%.2f".format(Locale.ROOT, v)`. Back it with a
     pure-Kotlin rounding implementation, verified byte-for-byte against the current output
     via the existing SVG snapshot tests (`kuml-io-svg/src/test/**` + vault-examples render
     tests). Replace all 44 `"%.2f".format(...)` sites.
   - Replace `System.err.println` in `UmlComponentSvg.kt` with `println` (or a warn shim).
   - After these, the entire renderer core has **zero** `java.*` references except the
     `toSvgFile` file-IO overloads.
   - `./gradlew clean check` + Renderer/Layout-Validierungs-Routine (regenerate PNGs,
     visually diff, confirm no coordinate drift from the new formatter).
2. **Prerequisite waves (separate):** KMP-migrate the JVM-only upstreams —
   `kuml-layout-api`, `kuml-layout-bridge`, `kuml-themes-core`, `kuml-metamodel-sysml2`,
   `kuml-metamodel-bpmn`, `kuml-metamodel-blueprint`, `kuml-render-smil`,
   `kuml-runtime-core` — each with its own java.* audit first.
3. **Later wave (once all `api()` upstreams are multiplatform):** flip
   `build.gradle.kts` to `alias(libs.plugins.kotlin.multiplatform)`, declare `jvm()` (plus
   `js()`/`wasmJs()` when targeted), move renderer core `src/main/kotlin` → `src/commonMain/kotlin`,
   move the `toSvgFile` overloads + any `Charsets`/`File`/`Path` code → `src/jvmMain/kotlin`,
   move `src/test/kotlin` → `src/commonTest/kotlin` (kotest multiplatform variants), keep the
   PNG co-generation (`kuml-io-png`) test dep in `jvmTest`.
4. **Verify** deterministic SVG output is byte-identical before/after via snapshot + vault
   render tests; copy refreshed SVGs to the presentation repo per the Renderer-Update routine.

## Feasibility verdict
- **Renderer core KMP-clean?** Yes, after centralizing the 44 `%.2f` format sites + the one
  `System.err` call. No date/time, big-number, UUID, regex, reflection, service-loader,
  threading, or `javax` dependencies exist.
- **PNG/file paths stay JVM-only?** Confirmed — only the `toSvgFile(...)` overloads in
  `KumlSvgRenderer.kt` touch `java.io.File` / `java.nio.file.Path`; they map cleanly to
  `jvmMain`.
- **Actual commonMain/jvmMain split this wave?** **No — blocked by the upstream dependency
  chain** (7 of 11 `api()` modules still `kotlin.jvm`). Deliver the audit + the safe
  platform-agnostic refactors now; perform the source-set split in a later wave once the
  upstream modules are multiplatform, mirroring the V3.2.6 kuml-core-dsl precedent.
