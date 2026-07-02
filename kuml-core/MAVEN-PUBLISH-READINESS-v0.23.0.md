# Maven Central Multi-Target Publish Readiness — v0.23.0

Status: **verification only — production publish NOT executed, pending manual approval.**

This document records the local verification performed for V3.2.15 of the
V3.2 Restposten Wellenplan ("Maven-Central-Multi-Target-Publish — NUR
Verifikation, KEIN Produktiv-Publish"). It intentionally deviates from the
original wave specification, which called for an actual production publish
to Maven Central plus a `commonMain.dev` re-submission. Per explicit
instruction, no real publish against Sonatype Central was performed in this
wave — see "Deviation from the wave specification" below.

## 1. Local verification performed

Command run (per module, chained into a single Gradle invocation), against
an **ephemeral, locally generated GPG test key** (2048-bit RSA, no
passphrase, 1-day expiry, discarded after the run) supplied via
`ORG_GRADLE_PROJECT_signingInMemoryKey` / `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`.
No real Sonatype credentials or the project's real GPG signing key were used
or required for this check:

```bash
./gradlew clean \
  :kuml-core:kuml-core-dsl:publishToMavenLocal \
  :kuml-core:kuml-core-model:publishToMavenLocal \
  :kuml-metamodel:kuml-metamodel-uml:publishToMavenLocal \
  :kuml-metamodel:kuml-metamodel-c4:publishToMavenLocal \
  :kuml-profile:kuml-profile-api:publishToMavenLocal \
  :kuml-core:kuml-core-expr:publishToMavenLocal \
  :kuml-metamodel:kuml-metamodel-kerml:publishToMavenLocal \
  :kuml-metamodel:kuml-metamodel-sysml2:publishToMavenLocal \
  :kuml-metamodel:kuml-metamodel-bpmn:publishToMavenLocal \
  :kuml-metamodel:kuml-metamodel-blueprint:publishToMavenLocal \
  :kuml-renderer:kuml-layout-api:publishToMavenLocal \
  :kuml-renderer:kuml-themes-core:publishToMavenLocal \
  :kuml-renderer:kuml-layout-bridge:publishToMavenLocal \
  :kuml-io:kuml-io-svg:publishToMavenLocal \
  --no-configuration-cache
```

Result: **`BUILD SUCCESSFUL`** (856 actionable tasks: 597 executed, 102 from
cache, 157 up-to-date). All 14 modules published into the local Maven
repository (`~/.m2/repository/dev/kuml/`) and were inspected before being
deleted again (this verification does not leave artefacts behind — the
local `~/.m2/repository/dev/kuml/` cache and the ephemeral GPG keyring were
both removed after the check).

### 1.1 Publication shape verified

For every one of the 14 in-scope modules (`kuml-core-dsl`, `kuml-core-model`,
`kuml-metamodel-uml`, `kuml-metamodel-c4`, `kuml-profile-api`,
`kuml-core-expr`, `kuml-metamodel-kerml`, `kuml-metamodel-sysml2`,
`kuml-metamodel-bpmn`, `kuml-metamodel-blueprint`, `kuml-layout-api`,
`kuml-themes-core`, `kuml-layout-bridge`, `kuml-io-svg`), vanniktech's
`KotlinMultiplatform(...)` publication shape produced exactly **4 Maven
coordinates**, each with a signed `.pom`/`.module`/artifact set:

| Coordinate suffix | Contents | Purpose |
|---|---|---|
| `<module>` (root) | `.pom`, `.module` (Gradle Module Metadata), `-sources.jar`, `-javadoc.jar` (empty), `-kotlin-tooling-metadata.json` | Root metadata artefact; `.module` declares `available-at` variants pointing at the three platform legs below |
| `<module>-jvm` | `.pom`, `.module`, `.jar`, `-sources.jar`, `-javadoc.jar` | JVM target |
| `<module>-js` | `.pom`, `.module`, `.klib`, `-sources.jar`, `-javadoc.jar` | JS (IR) target |
| `<module>-wasm-js` | `.pom`, `.module`, `.klib`, `-sources.jar`, `-javadoc.jar` | Wasm/JS target |

Every artefact in every leg was signed (`.asc` present next to each file) —
`signAllPublications()` in the root `build.gradle.kts` covers `jvm`, `js`,
`wasmJs`, and the root `kotlinMultiplatform` publication alike; there is no
gap where a leg would ship unsigned.

The root `.module` file for `kuml-core-dsl-0.22.0.module` (representative
sample, inspected via `python3 -m json.tool`) declares the expected
`available-at` redirects:

```
jvmApiElements-published (jvm)     -> ../../kuml-core-dsl-jvm/0.22.0/kuml-core-dsl-jvm-0.22.0.module
jsApiElements-published  (js)      -> ../../kuml-core-dsl-js/0.22.0/kuml-core-dsl-js-0.22.0.module
wasmJsApiElements-published (wasm) -> ../../kuml-core-dsl-wasm-js/0.22.0/kuml-core-dsl-wasm-js-0.22.0.module
```

This is the KMP-correct shape that ADR-0012 identified as **missing** in
the pre-migration JVM-only publication (which had only
`apiElements`/`runtimeElements` with `platform.type: jvm` and no
`commonMainMetadata` variant). The migration to
`kotlin("multiplatform")` (already completed prior to this wave — all 14
modules apply `alias(libs.plugins.kotlin.multiplatform)`) plus the
`pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform")` branch in
the root `build.gradle.kts` (lines ~175–181) together produce the
publication set that a KMP consumer's dependency resolver actually needs.

**Conclusion**: local verification confirms that a real
`publishToMavenCentral` run, using the real Sonatype credentials and the
real GPG signing key in CI, would produce the expected 4-artefact-set per
module on `repo1.maven.org` for all 14 modules. No structural, plugin
configuration, or signing-scope problem was found.

## 2. `skip-on-published` guard review (`.github/workflows/release.yml`)

The idempotency guard (lines 43–69) probes exactly **one** coordinate before
deciding whether to skip `publishToMavenCentral`:

```bash
POM_URL="https://repo1.maven.org/maven2/dev/kuml/kuml-core-dsl-jvm/${VERSION}/kuml-core-dsl-jvm-${VERSION}.pom"
```

This already targets the `-jvm` leg specifically (not the ambiguous root
coordinate) — a prior fix (documented inline in the workflow's comment,
V3.2.7) intentionally moved the probe from the root `kuml-core-dsl` POM to
`kuml-core-dsl-jvm`, because the root/metadata artefact can land even if a
platform leg (`-js`/`-wasm-js`) failed Central's component validation,
which would have made the former root-only probe a false positive for "fully
published".

**Risk confirmed as documented in the wave plan ("Stolperfalle: Idempotenz-Guard
nur gegen `-jvm`")**: the guard only proves that the **`-jvm`** leg reached
Central. It does **not** verify `-js` or `-wasm-js`. If a first production
publish attempt fails partway through — e.g. `-jvm` and `-js` succeed but
`-wasm-js` fails Central's validator, or Central's asynchronous validation
rejects one leg after upload — a second workflow run (retry) would see
`kuml-core-dsl-jvm-<version>.pom` return HTTP 200, conclude "already
published", and **skip** `publishToMavenCentral` entirely, leaving the
`-js`/`-wasm-js` legs (and any other of the 13 sibling modules) permanently
unpublished for that version. Because Maven Central is immutable, recovering
from that state requires a version bump for the missing legs, not a retry of
the same version.

This is exactly the risk the wave plan called out and is **not fixed in this
wave** — fixing it (e.g. probing all three per-module legs, or probing all
14 modules × 3 legs = 42 URLs before deciding to skip) is a real change to
release-critical CI logic that touches every future release, and per the
explicit constraint of this wave ("keine echten Produktiv-Publish-Änderungen
ohne Rücksprache"; only documentation + minimal config corrections in
scope) it is called out here for manual decision rather than silently
patched. Recommended follow-up (not applied): extend the guard to check
`kuml-core-dsl-jvm`, `kuml-core-dsl-js`, and `kuml-core-dsl-wasm-js` (or all
14 modules' `-jvm` leg as a cheaper partial improvement) and only report
`already_published=true` if all probes return HTTP 200.

## 3. Exact command that would perform the real production publish

**NOT executed in this wave.** For the record, the command CI already runs
(unchanged by this wave) is:

```bash
# Requires, as GitHub Actions secrets (see .github/workflows/release.yml lines 71-78):
#   ORG_GRADLE_PROJECT_mavenCentralUsername  <- secrets.SONATYPE_USERNAME
#   ORG_GRADLE_PROJECT_mavenCentralPassword  <- secrets.SONATYPE_PASSWORD
#   ORG_GRADLE_PROJECT_signingInMemoryKey    <- secrets.GPG_SIGNING_KEY
#   ORG_GRADLE_PROJECT_signingInMemoryKeyPassword <- secrets.GPG_PASSPHRASE
./gradlew publishToMavenCentral --no-configuration-cache
```

`--no-configuration-cache` is mandatory — vanniktech's Maven Central plugin
and Gradle's configuration cache are documented as incompatible; this is
already correctly set in `release.yml` and was not changed.

This command, run from CI on a tagged `v0.23.0` push, would publish **all**
non-excluded modules (the full `nonPublishedModules` exclusion list in the
root `build.gradle.kts` still applies), including the 14 KMP modules in
scope for this wave, each producing the 4-coordinate set verified in
section 1 above — this time against the real Sonatype Central Portal
instead of the local Maven cache.

## 4. Open prerequisites before a real production publish can happen

- **Secrets must exist and be valid** in the `kuml-dev/kUML` GitHub repo
  Actions secrets: `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`,
  `GPG_SIGNING_KEY`, `GPG_PASSPHRASE`. This wave did **not** verify these
  exist or are current (no access to repo secrets from this environment;
  verifying them requires either a GitHub Actions run or a maintainer with
  `gh secret list` access).
- **Immutability risk acknowledged**: once `v0.23.0` is tag-pushed and the
  publish step runs, none of the 14 modules' 4 coordinates can be
  overwritten for that version. A partial failure (see section 2) requires
  a version bump, not a retry-in-place.
- **Guard limitation accepted as-is for this wave** (see section 2) —
  recommended, not applied, fix: extend the probe to cover all three
  platform legs (or all 14 modules) before trusting "already published".
- **`commonMain.dev` re-submission** — explicitly out of scope for this
  verification-only pass. Per ADR-0012, the original `commonMain.dev`
  submission was already withdrawn on 2026-06-18 pending a real KMP
  artefact; the re-submission should happen only *after* a real production
  publish has been confirmed on `repo1.maven.org`, and the current
  `commonMain.dev` submission process should be re-checked at that time
  since third-party submission workflows can change without notice (as the
  wave plan itself flags as a "Stolperfalle").

## 5. Deviation from the wave specification

The wave specification (V3.2.15 in the V3.2 Restposten Wellenplan) describes
an **actual production publish** plus **`commonMain.dev` re-submission** as
in-scope deliverables. Per explicit operator instruction for this run, **no
real publish against the Sonatype Central Portal and no `commonMain.dev`
re-submission were performed or attempted.** This document, plus the local
`publishToMavenLocal` verification described in section 1, is a deliberate,
smaller substitute deliverable: it establishes readiness and identifies
open risk (section 2, section 4) without taking the irreversible step of a
real Maven Central publish. The real production publish and the
`commonMain.dev` re-submission remain to be executed by a maintainer after
manual review of this document, using the exact command in section 3.

**Production publish NOT executed — pending manual approval.**
