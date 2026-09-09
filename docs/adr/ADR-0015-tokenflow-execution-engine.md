# ADR-0015: Unified `TokenFlowEngine` for BPMN Process, UML Activity, and SysML 2 ACT

- **Status**: Accepted
- **Module**: `kuml-runtime-tokenflow` (new), consumed by `kuml-cli` (`kuml simulate`),
  `kuml-mcp` (`RuntimeSessionManager`), and `kuml-cli`'s `run` subcommand
  (`RunSessionManager`).

This is the design-decision record that ~40 KDoc comments across the codebase refer to
as "ADR-0015" — it did not exist as a written document before, which was itself flagged
in review. It documents the decisions those comments assume the reader already knows,
in particular the numbered sub-decisions (`SF-1`, `SF-9`, `SF-15`), the referenced
sections (`§3.3`, `§5`), and the two security fixes (`B2`, `B5`).

## Context

Before this change, `kuml simulate` could execute UML Activity and SysML 2 ACT diagrams
via `dev.kuml.runtime.activity.ActivityRuntime`, but BPMN Process diagrams had no
execution path at all — `kuml simulate` rejected them outright. Both BPMN and UML
Activity/SysML 2 ACT describe the same underlying computational model (a Petri-net-like
token flow over a directed graph of nodes and gateways); duplicating a second, BPMN-only
interpreter would have meant maintaining two slightly-different sets of firing rules,
trace formats, and security properties.

## Decision

Introduce a single, model-agnostic `TokenFlowEngine` (module `kuml-runtime-tokenflow`)
operating over a `TokenFlowSpec` — a paradigm-neutral graph of `TokenFlowNode`s and
`TokenFlowEdge`s. Three adapters translate the metamodel-specific structures into a
`TokenFlowSpec`:

- `BpmnTokenFlowAdapter` — BPMN Process → `TokenFlowSpec`.
- `UmlActivityTokenFlowAdapter` — UML Activity diagram → `TokenFlowSpec`.
- `ActivityRuntimeSpecBridge` (`ActivityRuntimeSpec.toTokenFlowSpec()`) — the existing
  SysML 2 ACT → `ActivityRuntimeSpec` path, bridged one hop further into the same spec.

`kuml simulate` routes a script to `TokenFlowEngine` whenever it resolves to a BPMN
Process diagram or a UML Activity diagram; SysML 2 ACT continues to run through
`ActivityRuntime` directly (the bridge exists for callers that want the unified engine's
API, e.g. future MCP tools), and UML/SysML 2 STM diagrams are unaffected (`StateMachineRuntime`
is a separate, unrelated interpreter).

The new engine emits exactly the `TraceEntry` variants `ActivityRuntime` already emits
(`TokenPlaced`, `TokenConsumed`, `ActivityActionInvoked`, `DecisionTaken`, `ForkSplit`,
`JoinReached`, `FlowFinalConsumed`, `ActivityTerminated`) — no new trace-entry types —
so `BpmnTokenTimelineBuilder` (SMIL animation) and `TraceFlavourDetector` work against
BPMN-produced traces without any change.

## Referenced sub-decisions

### SF-1 — `seqNo` equals `clock`, not a strictly-monotone index

`TraceEntry.seqNo` is set equal to the engine's logical `clock` on every entry, not to a
strictly-increasing per-entry counter. Multiple entries emitted within the same firing
step (or the same step across multiple ready nodes) can therefore share a `seqNo`. This
is a known inconsistency, kept intentionally rather than fixed, because `TraceDiff`'s
index-based comparison contract already depends on the legacy `ActivityRuntime`
producing traces this way — changing it here would break trace-diff parity between the
two engines rather than improve it.

### SF-9 — Degree counts come from `sequenceFlows`, never from `BpmnFlowNode.incoming`/`outgoing`

`BpmnTokenFlowAdapter` computes each node's in-degree/out-degree (used to infer implicit
gateway converging/diverging direction when a model doesn't set `GatewayDirection`
explicitly) by scanning `BpmnProcess.sequenceFlows`, never by reading
`BpmnFlowNode.incoming`/`BpmnFlowNode.outgoing`. DSL-built models (`ProcessBuilder`)
never populate those two list properties at all — they exist for BPMN-XML-imported
models — so relying on them would silently under-count every DSL-authored model. This
mirrors the same rule `BpmnToUmlActivityMapper` already follows for the same reason.

### SF-15 — Deterministic firing order

`TokenFlowSpec.outgoing`/`incoming` are built via `edges.groupBy { ... }`, which is
order-preserving in Kotlin, and `TokenFlowEngine.readyNodes()` sorts ready node ids
lexicographically before each step. Together these guarantee that the same model plus
the same event/guard context always produces byte-identical traces — required for
goldfile-based `--expected` trace comparison in CI.

### §3.3 — Execution limits (`TokenFlowLimits`)

`TokenFlowEngine.run()` enforces four independent limits every step, each surfaced as
its own `TokenFlowOutcome.LimitExceeded("<limit>", detail)` rather than a thrown
exception: `maxSteps` (iteration count), `maxTokens` (total concurrent tokens),
`maxTraceEntries` (trace size), and `wallClockBudgetMs` (real time, checked via an
injectable `nanoClock`). See `TokenFlowLimitsTest` (ADR-0015 B5, below) for why all four
are necessary rather than `maxSteps` alone.

### §5 — Guard evaluation is unconditionally sandboxed

Unlike the legacy STM/ACT path, where sandboxing (`TimeLimitedGuardEvaluator` wrapping
the guard evaluator) is opt-in via `kuml simulate --sandbox`, `TokenFlowEngine` has no
unsandboxed construction path at all: `TokenFlowEngine.sandboxed(...)` is the *only*
public factory (the constructor itself is `internal`), and it always wraps the guard
evaluator in `TimeLimitedGuardEvaluator`. `TokenFlowSandboxTest` verifies both that a
slow/blocking guard is actually cancelled at the timeout (not merely documented as such)
and, via `kotlin-reflect`'s `KVisibility`, that no public constructor bypassing the
wrapper exists — a JVM bytecode-modifier check would not catch this, since an `internal`
Kotlin constructor still compiles to a `public` JVM constructor.

### B2 — `guardEvaluator` was previously dead code in `ActivityRuntime`

Before this change, `ActivityRuntime` accepted a `guardEvaluator` constructor parameter
but its private `evaluateGuard` method called `dev.kuml.core.ocl.OclExpressions.evaluate`
directly instead of routing through the injected evaluator — so a caller wrapping it in
`TimeLimitedGuardEvaluator` (the CLI's `--sandbox` flag, `kuml run` ACT sessions, MCP
`RuntimeSessionManager` ACT sessions) had **no effect whatsoever**: a malicious or buggy
guard expression could hang activity execution indefinitely regardless of the flag. The
fix extracted the env-building logic into `ActivityGuardEvaluator` (shared with the new
engine's `TokenFlowGuardEvaluator`) and made `evaluateGuard` actually call
`guardEvaluator.evaluate(...)`. `ActivityRuntimeTest`'s "security fix B2 regression" test
pins this by injecting an evaluator that forcibly returns `False` for every guard and
asserting the run genuinely deadlocks instead of silently taking the OCL-true branch.
`SimulateCommand`'s `--sandbox`/`--guard-timeout-ms` flags were wired onto the ACT path
(`runActivity`) at the same time, for the same reason — they previously had no effect
there either. `RunSessionManager` and `RuntimeSessionManager` additionally force
sandboxing unconditionally for their long-lived / remotely-exposed ACT sessions,
independent of any CLI flag.

Security review of this branch found a fourth ACT call site that this list had missed:
`kuml trace replay` (`TraceReplayCommand.runActivityReplay`) built its `ActivityRuntime` via
the same `Sysml2ActivityAdapter.runtimeFor` and, having no `--sandbox`/`--guard-timeout-ms`
options at all, always got the unsandboxed default. Fixed the same way as
`RunSessionManager`/`RuntimeSessionManager`: sandboxing is unconditional (replay has no
legitimate reason to run a guard unsandboxed), with a `--guard-timeout-ms` option — matching
`SimulateCommand`'s naming — to tune the bound.

**Note (later fix, `!`-negation):** the B2 extraction above preserved
`ActivityGuardEvaluator`'s *historical* env-building and evaluation semantics verbatim —
including a latent bug in them: `!`-negated guards (`"!allow"`) silently evaluated to
`false` on every input, because this evaluator used only the `dev.kuml.core.ocl` front-end,
which lexes `!` as an error. This was invisible in `TokenFlowParityTest` and the "correct
guarded branch" fixtures above, because they only ever exercised the *un-negated* branch of
a guard pair — the negated edge happened to also correctly evaluate to `false` whenever the
un-negated one was `true`. `ActivityGuardEvaluator` was later given the same two-front-end
strategy `dev.kuml.runtime.OclGuardEvaluator` already had (typed AST first, OCL fallback),
so this ADR should not be read as evidence that the evaluator's dialect was ever fully
correct — only that its env-building (the actual subject of B2) was faithfully preserved.

### B5 — `maxSteps` alone does not stop a token-explosion DoS

A cyclic Fork (a `PARALLEL` diverging gateway inside a loop) can double the token count
every iteration while barely moving the step counter — `maxSteps` would let it run for a
very long time before tripping, consuming unbounded memory (`ActivityInstance.tokenCounts`)
and producing an unbounded trace in the meantime. `TokenFlowLimits.maxTokens` and
`maxTraceEntries` close that gap independently of `maxSteps`, and are checked every step
regardless of whether `maxSteps` would also eventually catch the same run.

## Consequences

- BPMN Process, UML Activity, and SysML 2 ACT now share one execution engine, one trace
  format, and one sandboxing doctrine — a bug fixed in `TokenFlowEngine` benefits all
  three metamodels at once, and a security property proven for one (e.g. "guard
  evaluation cannot hang the engine past its timeout") holds for all three.
- New per-metamodel behaviour (e.g. a BPMN-specific gateway type) still requires a
  metamodel-specific adapter change, but never a `TokenFlowEngine` change, keeping the
  engine itself free of `is BpmnX`/`is UmlY` branching.
- `TokenFlowEngine` deliberately does **not** replace `ActivityRuntime` for SysML 2 ACT
  execution in `Sysml2ActivityAdapter`/`kuml simulate`'s `runActivity` path — that
  continues to run directly through `ActivityRuntime`. `ActivityRuntimeSpecBridge`
  exists so a caller that specifically wants the unified engine's API/sandboxing story
  can still run a SysML 2 ACT model through it.
