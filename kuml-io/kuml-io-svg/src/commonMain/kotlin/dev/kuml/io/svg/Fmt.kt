package dev.kuml.io.svg

/**
 * Formats an SVG coordinate/dimension value with a locale-independent decimal point,
 * rounded to 2 decimal places. Whole numbers are rendered without a fractional part
 * (e.g. `120f` -> `"120"`, `120.5f` -> `"120.50"`).
 *
 * Multiplatform-safe: previously used `String.format` + `Locale.ROOT` (JVM-only);
 * V3.2.8/9 KMP migration replaced this with [roundToDecimals], a manual
 * fixed-point formatter that works on jvm/js/wasmJs alike.
 */
internal fun fmt2(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else roundToDecimals(v, 2)
}

/**
 * Formats a value with a locale-independent decimal point, rounded to 3
 * decimal places. Whole numbers are rendered without a fractional part.
 *
 * Multiplatform-safe replacement for the 12 file-local `String.format(Locale.US,
 * "%.3f", v)` duplicates found across the sysml2/uml SVG renderers (V3.2.8/9
 * KMP migration) — `java.util.Locale` and `String.format` are JVM-only.
 */
internal fun fmt3(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) i.toString() else roundToDecimals(v, 3)
}

/**
 * Locale-independent decimal rounding: multiplies by 10^decimals, rounds to
 * the nearest Long, then reassembles the integer/fractional parts as a
 * string. Avoids `String.format`/`Locale`, both JVM-only APIs.
 */
private fun roundToDecimals(
    v: Float,
    decimals: Int,
): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = v.toDouble() * factor
    val rounded = if (scaled >= 0) (scaled + 0.5).toLong() else (scaled - 0.5).toLong()
    val negative = rounded < 0
    val abs = if (negative) -rounded else rounded
    val whole = abs / factor
    val frac = abs % factor
    val fracStr = frac.toString().padStart(decimals, '0')
    return buildString {
        if (negative) append('-')
        append(whole)
        append('.')
        append(fracStr)
    }
}
