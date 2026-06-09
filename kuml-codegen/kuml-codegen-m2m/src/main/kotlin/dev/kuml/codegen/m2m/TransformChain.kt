package dev.kuml.codegen.m2m

/**
 * Sequences two transformers, threading the traceability through both steps.
 *
 * The output of [first] becomes the input of [second]. If [first] fails, [second]
 * is never called and the failure is propagated immediately. If [second] fails
 * after [first] succeeds, the second failure is returned (the first step's trace
 * is discarded because there is no combined output to attach it to).
 *
 * Successful chains merge the traces of both steps.
 */
public class TransformChain<A, B, C>(
    private val first: KumlTransformer<A, B>,
    private val second: KumlTransformer<B, C>,
) : KumlTransformer<A, C> {
    override val id: String = "${first.id}+${second.id}"
    override val description: String = "Chain: ${first.description} → ${second.description}"

    override fun transform(
        source: A,
        ctx: TransformContext,
    ): TransformResult<C> {
        val r1 = first.transform(source, ctx)
        if (r1 is TransformResult.Failure) return r1
        val s1 = (r1 as TransformResult.Success).output
        return when (val r2 = second.transform(s1, ctx)) {
            is TransformResult.Success ->
                TransformResult.Success(
                    r2.output,
                    TransformTrace(r1.trace.links + r2.trace.links),
                )
            is TransformResult.Failure -> r2
        }
    }
}
