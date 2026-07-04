package dev.kuml.core.script.interpreter

/**
 * Abstract syntax tree for the **kUML data-DSL interpreter subset** (Welle 9,
 * Option D of the MCP-Sandbox architecture).
 *
 * ## Why an AST at all
 *
 * The MCP script channel historically ran arbitrary Kotlin through the embedded
 * Kotlin compiler (`KumlScriptHost.eval`) — a full RCE surface. Wellen 1-8 built
 * containment layers (denylist, child-process, OS cages, classloader allowlist)
 * *around* that compiler. This module is the strategic endgame (Option D): a
 * parser + interpreter that never compiles or executes JVM bytecode at all.
 *
 * The interpreter only understands a **finite grammar** whose only "verbs" are a
 * fixed allowlist of kUML DSL builder names. `Runtime::class.java` or
 * `ProcessBuilder(...)` are not rejected by a filter — they simply have **no
 * production rule** in this grammar, so they are ordinary parse errors. That is
 * the structural difference from a denylist: impossible, not filtered.
 *
 * ## Deliberately small
 *
 * This AST models exactly the syntactic shapes that real kUML **class-diagram**
 * vault scripts use (see `01 UML Klasse – Order Domain.md`): `val` bindings,
 * positional + named call arguments, trailing-lambda blocks, string/int/boolean
 * literals, dotted enum-member references (`Visibility.PROTECTED`), identifier
 * references to earlier `val`s, and property assignments inside a builder body
 * (`isAbstract = true`). It is **not** a Kotlin AST — there are no operators,
 * conditionals, loops, function definitions, lambdas-as-values, or arbitrary
 * method chains. Those are out of scope by design (see [InterpreterScriptEvaluator]).
 *
 * V0.23.3 — Welle 9.
 */
internal sealed interface DslNode

/** A whole parsed script: the top-level entry call (e.g. `classDiagram(...) { ... }`). */
internal data class DslScript(
    val root: DslCall,
) : DslNode

/**
 * One statement inside a builder block body. Either a `val` binding, a bare
 * call, or a property assignment on the implicit receiver.
 */
internal sealed interface DslStatement : DslNode

/** `val name = <call>` — binds the call's result handle to [name] in the scope. */
internal data class DslValBinding(
    val name: String,
    val value: DslCall,
    val line: Int,
) : DslStatement

/** A bare builder call statement, e.g. `attribute(name = "id", type = "UUID")`. */
internal data class DslCallStatement(
    val call: DslCall,
) : DslStatement

/**
 * A property assignment on the implicit builder receiver, e.g. `isAbstract = true`
 * or `aggregation = AggregationKind.COMPOSITE`.
 */
internal data class DslPropertyAssignment(
    val property: String,
    val value: DslExpr,
    val line: Int,
) : DslStatement

/**
 * A builder call: `name(arg, key = arg, ...) { body }`.
 *
 * @property name the callee — must be an allowlisted builder name.
 * @property args positional and/or named arguments.
 * @property body optional trailing-lambda body (the `{ ... }`), or null.
 */
internal data class DslCall(
    val name: String,
    val args: List<DslArg>,
    val body: List<DslStatement>?,
    val line: Int,
) : DslExpr

/** A call argument: positional (name == null) or named. */
internal data class DslArg(
    val name: String?,
    val value: DslExpr,
)

/** An expression usable as an argument value or assignment RHS. */
internal sealed interface DslExpr : DslNode

internal data class DslString(
    val value: String,
) : DslExpr

internal data class DslInt(
    val value: Long,
) : DslExpr

internal data class DslBool(
    val value: Boolean,
) : DslExpr

/** A bare identifier — a reference to an earlier `val` handle, e.g. `order`. */
internal data class DslIdentifier(
    val name: String,
    val line: Int,
) : DslExpr

/**
 * A dotted member reference such as `Visibility.PROTECTED` or
 * `AggregationKind.COMPOSITE`. [qualifier] is the enum type, [member] the value.
 */
internal data class DslMemberRef(
    val qualifier: String,
    val member: String,
    val line: Int,
) : DslExpr
