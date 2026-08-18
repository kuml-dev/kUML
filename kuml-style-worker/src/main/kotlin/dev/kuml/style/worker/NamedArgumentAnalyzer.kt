package dev.kuml.style.worker

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * A single positional-argument finding, expressed in offsets **within the
 * wrapped source** ([KumlScriptWrapper.wrapKumlScript]'s output) — callers
 * map [offsetInWrappedSource] back to the original script's line/column via
 * a constant `- prefixLen` subtraction (see [WrappedKumlScript]).
 */
internal data class NamedArgumentFinding(
    val offsetInWrappedSource: Int,
    val paramName: String,
    val calleeFqName: String,
)

/**
 * The fully-qualified package prefixes considered "kUML's own code" for the
 * style check. Hardcoded (not configurable) — the CLI/MCP-facing opt-out is
 * a whole-check on/off switch ([dev.kuml.core.script.style.NamedArgumentStyleCheck]'s
 * caller), not a scope knob. Mirrors `RequireNamedArguments`'s default
 * (`:kuml-detekt-rules`), which IS configurable there because that rule
 * targets kUML's own multi-repo codebase, not third-party DSL scripts.
 */
private val OWNED_PACKAGE_PREFIXES = listOf("dev.kuml.")

/**
 * Walks every [KtCallExpression] in [ktFile] and reports one
 * [NamedArgumentFinding] per positional value argument passed to a
 * kUML-owned (`dev.kuml.*`) function or constructor that declares more than
 * one value parameter.
 *
 * This is a `KtTreeVisitorVoid`-driven **reimplementation** of
 * `dev.kuml.detekt.RequireNamedArguments`'s `visitCallExpression` logic
 * (`:kuml-detekt-rules`) — not a call into it. That rule is a `detekt.api.Rule`
 * tied to the detekt Gradle pipeline; this worker instead drives the same
 * Analysis API resolution directly over a standalone session built from
 * wrapped `*.kuml.kts` source (see [KumlAnalysisSession]). The exemption
 * logic below is kept in lockstep with `RequireNamedArguments` by design —
 * see `RequireNamedArgumentsSpec` (detekt-side) and this module's own test
 * suite (worker-side) for the matching case coverage.
 *
 * Must be called from inside an `analyze { }` context is NOT required here —
 * each call expression opens its own `analyze(expression) { }` block
 * internally, matching the detekt rule's per-expression resolution.
 */
internal fun analyzeNamedArguments(ktFile: KtFile): List<NamedArgumentFinding> {
    val findings = mutableListOf<NamedArgumentFinding>()
    ktFile.accept(
        object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                analyze(expression) {
                    val call = expression.resolveToCall()?.successfulFunctionCallOrNull() ?: return
                    val symbol = call.symbol

                    // Constructors have no callableId — fall back to the
                    // containing class id, otherwise every constructor call
                    // is missed.
                    val fqName =
                        when (symbol) {
                            is KaConstructorSymbol -> symbol.containingClassId?.asSingleFqName()?.asString()
                            else -> symbol.callableId?.asSingleFqName()?.asString()
                        } ?: return
                    if (OWNED_PACKAGE_PREFIXES.none { fqName.startsWith(it) }) return

                    val named = symbol as? KaNamedFunctionSymbol
                    if (named != null && (named.isOperator || named.isInfix)) return
                    if (symbol.valueParameters.size <= 1) return

                    for ((argExpr, paramSig) in call.valueArgumentMapping) {
                        val param = paramSig.symbol
                        if (param.isVararg) continue
                        val valueArg = argExpr.parent as? KtValueArgument ?: continue
                        if (valueArg is KtLambdaArgument) continue // block-DSL trailing lambda
                        if (valueArg.isNamed()) continue

                        findings +=
                            NamedArgumentFinding(
                                offsetInWrappedSource = valueArg.textRange.startOffset,
                                paramName = param.name.asString(),
                                calleeFqName = fqName,
                            )
                    }
                }
            }
        },
    )
    return findings
}

/**
 * Maps an offset within the wrapped source ([wrapKumlScript]'s output) back
 * to a 1-based (line, column) position in the **original** script text.
 *
 * The mapping is a constant subtraction (`offsetInWrappedSource - prefixLen`)
 * because [wrapKumlScript] only ever blanks lines in place — it never
 * inserts or removes a character within the original body, so every
 * remaining character's relative offset from the start of the body is
 * unchanged.
 */
internal fun mapToOriginalLineColumn(
    originalSource: String,
    offsetInWrappedSource: Int,
    prefixLen: Int,
): Pair<Int, Int> {
    val offset = (offsetInWrappedSource - prefixLen).coerceIn(0, originalSource.length)
    val before = originalSource.substring(0, offset)
    val line = before.count { it == '\n' } + 1
    val column = offset - (before.lastIndexOf('\n') + 1) + 1
    return line to column
}
