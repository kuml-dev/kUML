package dev.kuml.jetbrains

import java.io.File
import dev.kuml.langsupport.cli.KumlCliLocator as Shared

/**
 * IntelliJ-side adapter over the platform-neutral [Shared] locator: injects the
 * user-configured path from [KumlPreviewSettings] so call sites can keep
 * writing `KumlCliLocator.resolve(hintDir)` unchanged.
 *
 * The actual resolution algorithm (explicit override → configured path → PATH
 * → common locations → local-build walk-up) lives in `kuml-lang-support` so it
 * can be shared with the Wave 2 LSP server without an IntelliJ Platform dependency.
 */
internal object KumlCliLocator {
    fun resolve(searchHintDir: File?): File? = Shared.resolve(searchHintDir, KumlPreviewSettings.cliPath())
}
