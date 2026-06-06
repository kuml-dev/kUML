package dev.kuml.jetbrains

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Decorates `*.kuml.kts` files with a distinct icon.
 *
 * Why a `FileIconProvider` and not a custom `FileType`?
 *  - The file type is "Kotlin Script" (registered by the Kotlin plugin) — we
 *    want to inherit lexer/parser/syntax-highlighting for free, so we **do not**
 *    register our own `LanguageFileType`.
 *  - `FileIconProvider` runs alongside the file-type registry and lets us
 *    override only the icon, leaving everything else untouched.
 */
public class KumlFileIconProvider : FileIconProvider {
    override fun getIcon(
        file: VirtualFile,
        flags: Int,
        project: Project?,
    ): Icon? {
        if (!file.name.endsWith(".kuml.kts")) return null
        return KumlIcons.SCRIPT
    }
}

/**
 * Icon registry for the kUML plugin.
 *
 * Icons are loaded lazily via [IconLoader.getIcon] so resource loading happens
 * on first paint rather than at class-init time — keeps the plugin start cheap.
 */
public object KumlIcons {
    /** File icon for `*.kuml.kts`. */
    public val SCRIPT: Icon = IconLoader.getIcon("/icons/kuml-script.svg", KumlIcons::class.java)
}
