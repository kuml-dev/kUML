package dev.kuml.expr

/**
 * Type universe for the kUML typed expression AST (V2.0.20a).
 *
 * [Unknown] represents an unresolvable [AttributeRef] or [FunctionCall] — not
 * an error in V2.0.20a scope; full scope resolution is deferred to V2.0.20b.
 */
public sealed class KumlType {
    public object Bool : KumlType()

    public object Int : KumlType()

    public object Real : KumlType()

    public object Str : KumlType()

    public object Null : KumlType()

    /** Unresolved [AttributeRef] or [FunctionCall] — not an error in V2.0.20a. */
    public object Unknown : KumlType()

    public data class TypeError(
        val message: String,
        val column: kotlin.Int = -1,
    ) : KumlType()
}
