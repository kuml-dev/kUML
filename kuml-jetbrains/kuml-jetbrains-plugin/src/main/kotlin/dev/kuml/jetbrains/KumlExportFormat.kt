package dev.kuml.jetbrains

/**
 * Export formats supported by the `kuml render -f <format>` CLI option.
 *
 * [cliFormat] is the token passed to `-f`. [extension] is the expected output
 * file extension. [displayName] is shown in the Export toolbar menu.
 *
 * Note: the CLI format token for TeX is `latex`, but the output file uses the
 * `.tex` extension — [KumlExportFormat.TEX] centralises this mapping to avoid
 * the classic `-f tex`-vs-`.tex`-extension mixup.
 */
enum class KumlExportFormat(
    val cliFormat: String,
    val extension: String,
    val displayName: String,
) {
    SVG("svg", "svg", "SVG"),
    PNG("png", "png", "PNG"),
    TEX("latex", "tex", "TeX (LaTeX)"),
    APNG("apng", "apng", "Animated PNG"),
    WEBP("webp", "webp", "Animated WebP"),
    ;

    companion object {
        /** Returns the entry whose [extension] matches [ext], or `null`. */
        fun fromExtensionOrNull(ext: String): KumlExportFormat? = entries.firstOrNull { it.extension == ext }
    }
}
