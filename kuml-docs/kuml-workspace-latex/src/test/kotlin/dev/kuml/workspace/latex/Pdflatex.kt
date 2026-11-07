package dev.kuml.workspace.latex

import java.io.File

// Keep this file byte-identical (apart from the package line) with the copies in
// kuml-io-latex, kuml-workspace-latex and kuml-cli. scripts/ci/check_latex_tests.py enforces it.

/**
 * Locates `pdflatex` for the PDF build tests and decides what a missing TeX installation means.
 *
 * Default: no TeX means the tests are skipped. With `-Pkuml.latex.require=true` (forwarded by the build as the
 * system property `kuml.latex.require`) a missing TeX is a hard failure instead, so CI cannot go green silently.
 */
internal object Pdflatex {
    /** The pdflatex found on PATH or in the usual TeX locations, or null. */
    val executable: File? =
        (System.getenv("PATH").orEmpty().split(File.pathSeparator) + "/Library/TeX/texbin" + "/usr/bin" + "/usr/local/bin")
            .map { File(it, "pdflatex") }
            .firstOrNull { it.canExecute() }

    /** True when `-Dkuml.latex.require=true`: a missing pdflatex must fail, never skip. */
    val required: Boolean = System.getProperty("kuml.latex.require") == "true"

    /** For `.config(enabled = ...)`: run when TeX is present, or when strict mode wants the failure. */
    val enabled: Boolean get() = executable != null || required

    /** Returns pdflatex, or fails the test with a clear message in strict mode (never reached when skipped). */
    fun requireExecutable(): File =
        executable ?: error("pdflatex not found on PATH, /Library/TeX/texbin, /usr/bin or /usr/local/bin, but kuml.latex.require=true")
}
