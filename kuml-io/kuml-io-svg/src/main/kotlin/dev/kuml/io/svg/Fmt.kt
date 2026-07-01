package dev.kuml.io.svg

/**
 * Formats an SVG coordinate/dimension value with a locale-independent decimal point,
 * rounded to 2 decimal places. Whole numbers are rendered without a fractional part
 * (e.g. `120f` -> `"120"`, `120.5f` -> `"120.50"`).
 *
 * Centralizes the single JVM-only concern (`String.format` + `Locale.ROOT`) that
 * otherwise would be duplicated across every SVG renderer file. See
 * `kuml-io/kuml-io-svg/KMP-AUDIT.md` for the KMP-migration rationale.
 */
internal fun fmt2(v: Float): String {
    val i = v.toInt()
    return if (v == i.toFloat()) "$i" else "%.2f".format(java.util.Locale.ROOT, v)
}
