package dev.kuml.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.config
import dev.detekt.api.modifiedText
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Pure text splice: inserts each `"paramName = "` string at its recorded offset, applied
 * strictly descending so earlier offsets stay valid. Never moves, reorders, wraps or
 * reformats existing text — this is the property that makes mass autocorrect safe: Kotlin
 * evaluates arguments in call-site textual order, and a pure insertion cannot change that
 * order (see CLAUDE.md cleanup notes for `RequireNamedArguments`).
 *
 * Extracted as a standalone, directly unit-testable function (rather than only reachable
 * through the full detekt Gradle pipeline) — see [RequireNamedArgumentsSpec].
 */
internal fun spliceNamedArguments(
    text: String,
    inserts: List<Pair<Int, String>>,
): String {
    var result = text
    inserts.sortedByDescending { it.first }.forEach { (offset, insert) ->
        result = result.substring(0, offset) + insert + result.substring(offset)
    }
    return result
}

/**
 * Every value argument passed to a kUML-owned function or constructor must be named,
 * whenever the callee declares more than one value parameter.
 *
 * See CLAUDE.md "Kotlin-Coding-Konventionen / 1. Named Parameters — PFLICHT".
 */
class RequireNamedArguments(
    config: Config,
) : Rule(
        config,
        "kUML-owned calls with more than one value parameter must use named arguments.",
    ),
    RequiresAnalysisApi {
    @Configuration("Fully-qualified package prefixes considered 'kUML's own code'.")
    private val ownedPackagePrefixes: List<String> by config(listOf("dev.kuml."))

    /** (insertOffset, "paramName = ") pairs collected for the file currently being visited. */
    private val pendingInserts = mutableListOf<Pair<Int, String>>()

    override fun visitKtFile(file: KtFile) {
        pendingInserts.clear()
        super.visitKtFile(file)
        if (autoCorrect && pendingInserts.isNotEmpty()) {
            // Deliberately NOT PsiElement.replace() — that routes through
            // CodeEditUtil/CodeStyleManager, which detekt's standalone analysis
            // session does not register, and throws a NullPointerException.
            file.modifiedText = spliceNamedArguments(file.text, pendingInserts)
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        analyze(expression) {
            val call = expression.resolveToCall()?.successfulFunctionCallOrNull() ?: return
            val symbol = call.symbol

            // ── "is this kUML's own function?" ────────────────────────────────
            // Constructors have NO callableId (it is null) — fall back to the
            // containing class id, otherwise every constructor call is missed.
            val fqName =
                when (symbol) {
                    is KaConstructorSymbol -> symbol.containingClassId?.asSingleFqName()?.asString()
                    else -> symbol.callableId?.asSingleFqName()?.asString()
                } ?: return
            if (ownedPackagePrefixes.none { fqName.startsWith(it) }) return

            // ── exemptions on the declaration ─────────────────────────────────
            val named = symbol as? KaNamedFunctionSymbol
            if (named != null && (named.isOperator || named.isInfix)) return
            if (symbol.valueParameters.size <= 1) return

            // ── exemptions per argument ───────────────────────────────────────
            for ((argExpr, paramSig) in call.valueArgumentMapping) {
                val param = paramSig.symbol
                if (param.isVararg) continue
                val valueArg = argExpr.parent as? KtValueArgument ?: continue
                if (valueArg is KtLambdaArgument) continue // block-DSL trailing lambda
                if (valueArg.isNamed()) continue

                pendingInserts += valueArg.textRange.startOffset to "${param.name.asString()} = "
                report(
                    Finding(
                        Entity.from(valueArg),
                        "Argument for '${param.name}' of '$fqName' is passed positionally; " +
                            "use a named argument (CLAUDE.md: Named Parameters — PFLICHT).",
                    ),
                )
            }
        }
    }
}
