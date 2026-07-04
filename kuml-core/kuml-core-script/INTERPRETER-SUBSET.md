# kUML data-DSL interpreter — supported subset (Welle 9, Option D)

This module contains an **experimental, opt-in** script-evaluation strategy for
the MCP channel that **interprets** the kUML DSL instead of compiling and running
it through the embedded Kotlin compiler.

- Package: `dev.kuml.core.script.interpreter`
- Entry point: `InterpreterScriptEvaluator` (a `ScriptEvaluator`)
- Selected by: `KUML_MCP_SANDBOX_EVAL_STRATEGY=interpreter`
  (default `compiler` — the interpreter is **never** the production default)

## Why it exists

Wellen 1-8 hardened the *compiler* path with containment layers (denylist,
child-process, OS cages, classloader allowlist) — but that path still runs
untrusted Kotlin through a real compiler, so RCE remains *possible-but-contained*.
This interpreter removes the compiler from the loop entirely. Its grammar has a
**finite** set of productions whose only verbs are an allowlist of real kUML DSL
builder names. `Runtime::class.java...` is not filtered — it simply has no
production rule, so it is a plain parse error. **RCE is structurally impossible on
this path, not merely blocked.**

## Architecture

`DslLexer` → `DslParser` (→ `DslAst`) → `DslInterpreter` → real DSL builders →
`KumlDiagram`. Modelled on the existing `OclLikeExpressionParser` in
`kuml-core-expr` (kUML's precedent for "AST + interpreter, no compiler"), but for
model *construction* rather than expression evaluation.

The interpreter **drives the real DSL builders** (`ClassDiagramBuilder`,
`classOf`, `enumOf`, `association`, …). It does not re-implement the metamodel.
Consequence: for supported scripts it produces a `KumlDiagram` **byte-identical**
to the compiler path (proven by `InterpreterVsCompilerTest` on the unmodified
`01 UML Klasse – Order Domain.md` vault script).

## Supported language subset

- `val name = <builderCall>` bindings (diagram scope only; no re-binding)
- builder calls with **positional and named** arguments, in any mix
- **trailing-lambda** bodies: `foo(args) { ... }` and the parenthesis-less form
  `source { ... }`
- literals: **string** (`"..."`), **integer** (`42`, `-3`), **boolean**
  (`true`/`false`)
- **property assignment** on the implicit receiver: `isAbstract = true`,
  `aggregation = AggregationKind.COMPOSITE`, `showOperations = false`
- **enum member references**: `Visibility.PROTECTED`, `AggregationKind.COMPOSITE`
- **`val`-handle references** as arguments: `association(source = order, ...)`
- `;` as a statement separator on a single line
- line (`//`) and block (`/* */`) comments

## Supported DSL vocabulary (UML class diagrams only)

| Scope | Allowlisted builders / properties |
|---|---|
| top-level | `classDiagram(name = ...)` |
| diagram body | `classOf`, `interfaceOf`, `enumOf`, `association`, `generalization`, `realization`, `dependency`, `comment`; properties `showAttributes`, `showOperations`, `showVisibility`, `showPackageNames`, `mergeEdges` |
| class body | `attribute`, `operation`, `constraint`, `extends`, `implements`; properties `isAbstract`, `visibility` |
| interface body | `attribute`, `operation`, `constraint` |
| enum body | `literal` |
| operation body | `parameter`, `returns`; properties `visibility`, `isAbstract`, `isStatic` |
| association body | `source`, `target`; properties `name`, `aggregation` |
| association end | `multiplicity(spec = ...)`; properties `role`, `navigable` |
| enums | `Visibility.{PUBLIC,PRIVATE,PROTECTED,PACKAGE}`, `AggregationKind.{NONE,SHARED,COMPOSITE}` |

## Explicitly NOT supported (out of scope for this slice)

Any of these fail with a clear `FailureKind.EVALUATION` message telling the
caller to use `--eval-strategy=compiler` — never a crash, never a silent fallback:

- **All non-class diagram types**: `c4Model`, `sysml2Model`, `bpmnModel`,
  `blueprint`, `useCaseDiagram`, `stateDiagram`, `sequenceDiagram`,
  `componentDiagram`, `packageDiagram`, `objectDiagram`, `deploymentDiagram`, … —
  recognised by name and rejected specifically.
- Turing-complete Kotlin: **loops, conditionals, function/lambda definitions,
  arithmetic, operators, indexing, method chains** beyond a single enum member
  reference.
- **String interpolation** (`"$x"`), decimal number literals as bare tokens
  (wrap in a string, e.g. `defaultValue = "0.19"`).
- Reflection / arbitrary constructors / any JVM API — no production exists.
- `val` bindings inside builder bodies (only diagram-scope `val`s).
- Multi-anchor `comment(...)` (only zero or one `firstAnchor`).
- Stereotypes / profiles / layout hints / the block-form `attribute { ... }`.

## Maturity

This is an **honest partial result** (see the architecture note's Welle-9
entry). It is a real, tested interpreter for a documented class-diagram subset —
not a complete DSL re-implementation. It is opt-in and does not replace the
fail-closed compiler path.
