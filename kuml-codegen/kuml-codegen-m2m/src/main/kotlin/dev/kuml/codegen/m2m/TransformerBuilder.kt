package dev.kuml.codegen.m2m

/**
 * A declarative rule inside a rule-based [KumlTransformer] implementation.
 *
 * @property id Stable identifier for traceability links.
 * @property matches Predicate that selects elements handled by this rule.
 * @property guard Secondary predicate that can access [TransformContext].
 * @property produce Produces the target artifact for a matched element.
 */
public class TransformerRule<S, T>(
    public val id: String,
    public val matches: (S) -> Boolean,
    public val guard: (S, TransformContext) -> Boolean = { _, _ -> true },
    public val produce: (S, TransformContext) -> T,
)

/**
 * Lightweight builder that collects [TransformerRule]s for a [KumlTransformer].
 *
 * Usage (inside a transformer's init block):
 * ```kotlin
 * val rules = TransformerBuilder<UmlClass, GeneratedFile>().apply {
 *     rule("uml-class") {
 *         match { it is UmlClass }
 *         produce { element, _ -> GeneratedFile("${element.name}.kt", "class ${element.name}") }
 *     }
 * }.rules()
 * ```
 */
public class TransformerBuilder<S, T> {
    private val rules = mutableListOf<TransformerRule<S, T>>()

    /** Defines and registers a new rule. */
    public fun rule(
        id: String,
        block: RuleBuilder<S, T>.() -> Unit,
    ): TransformerRule<S, T> {
        val rb = RuleBuilder<S, T>(id).apply(block)
        return rb.build().also { rules += it }
    }

    /** Returns the immutable list of registered rules in declaration order. */
    public fun rules(): List<TransformerRule<S, T>> = rules.toList()
}

/**
 * Builder for a single [TransformerRule].
 *
 * All three clauses ([match], [guard], [produce]) are optional except [produce],
 * which is required — building without it throws [IllegalStateException].
 */
public class RuleBuilder<S, T>(
    private val id: String,
) {
    private var matchFn: (S) -> Boolean = { true }
    private var guardFn: (S, TransformContext) -> Boolean = { _, _ -> true }
    private var produceFn: ((S, TransformContext) -> T)? = null

    /** Sets the match predicate. */
    public fun match(fn: (S) -> Boolean) {
        matchFn = fn
    }

    /** Sets the context-aware guard predicate. */
    public fun guard(fn: (S, TransformContext) -> Boolean) {
        guardFn = fn
    }

    /** Sets the produce function (required). */
    public fun produce(fn: (S, TransformContext) -> T) {
        produceFn = fn
    }

    internal fun build(): TransformerRule<S, T> =
        TransformerRule(id, matchFn, guardFn, produceFn ?: error("Rule '$id' missing produce block"))
}
