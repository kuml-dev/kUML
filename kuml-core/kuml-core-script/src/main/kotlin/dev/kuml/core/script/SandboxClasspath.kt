package dev.kuml.core.script

import java.io.File

/**
 * Builds the **curated dependency classpath** handed to the Kotlin script
 * compiler for the sandbox worker path (Welle 7, layer B).
 *
 * The historical config uses `dependenciesFromCurrentContext(wholeClasspath = true)`
 * (see [KumlScriptCompilationConfiguration]) — every jar on the worker JVM's
 * classpath is visible to the untrusted script, including JNA, the whole
 * kotlin-compiler-embeddable, coroutines-debug, etc. A script can reference any
 * class in any of those jars.
 *
 * This object narrows that set to only the jars a **legitimate** kUML DSL script
 * needs. Anything not matched is simply not on the compiler's dependency
 * classpath, so a reference to one of its classes is an **unresolved reference at
 * compile time** — the strongest, earliest possible rejection (before the script
 * body ever runs), and — crucially — it works against **classpath** classes,
 * which the [AllowlistClassLoader] base-loader filter alone cannot stop (the
 * compiled-script loader resolves its own classpath URLs without consulting the
 * base loader).
 *
 * ## What is deliberately included
 *
 * Derived from `KumlScript.kt`'s `defaultImports` (all `dev.kuml.*` DSL +
 * metamodel packages) plus the runtime the constructed model objects need:
 *  - every `kuml-*` jar (the DSL builders + metamodels + expr/profile deps),
 *  - `kotlin-stdlib` and `kotlin-reflect` (the DSL + `DiagramExtractor` reflection),
 *  - `kotlinx-serialization-*`, `kotlinx-io-*`, `atomicfu-*` (the `@Serializable`
 *    metamodel types + their transitive value deps),
 *  - `annotations-*` (JetBrains `@NotNull` etc. referenced by stdlib metadata).
 *
 * ## What is deliberately excluded
 *
 *  - `jna` / `jna-platform` — native bridge, no place in a DSL script.
 *  - `kotlin-compiler-embeddable`, `kotlin-scripting-*` — the compiler/host's own
 *    machinery; a script that could reach these could compile+eval *more* code.
 *  - `kotlinx-coroutines-*` (esp. `-debug`, which pulls in a java-agent/attach
 *    surface) — the DSL is synchronous and deterministic; scripts do not need it.
 *  - the test classpath (`.../build/classes/.../test`, kotest, junit).
 *
 * ## Honest limit
 *
 * This does **not** and **cannot** remove JDK platform modules (`java.*`, most
 * `jdk.*`): those live in the boot module layer, not on any classpath, and are
 * resolved by neither the dependency classpath nor the base loader. `java.io.File`,
 * `java.net.Socket`, `java.lang.Runtime` remain referenceable. Neutralising their
 * *effects* is the OS cage's job (Wellen 4-6); this layer only shrinks the
 * **non-JDK** attack surface a script can name.
 *
 * V0.23.3 — Welle 7.
 */
internal object SandboxClasspath {
    /**
     * Filename prefixes (jar basename, case-insensitive) of the dependencies a
     * legitimate kUML DSL script may reference. A classpath entry is kept iff its
     * basename starts with one of these OR it is a `kuml-*` module. Everything
     * else is dropped.
     */
    val ALLOWED_JAR_PREFIXES: List<String> =
        listOf(
            "kotlin-stdlib",
            "kotlin-reflect",
            "kotlinx-serialization-",
            "kotlinx-io-",
            "atomicfu",
            "annotations-", // org.jetbrains:annotations, referenced by stdlib metadata
        )

    /**
     * Directory-classpath basenames that are legitimate (the DSL/metamodel module
     * output dirs during a Gradle test/run). We keep any directory whose path
     * contains `kuml` (the project's own compiled classes) and drop test output.
     */
    private fun isAllowedDirectory(entry: File): Boolean {
        if (!entry.isDirectory) return false
        val path = entry.path.replace('\\', '/')
        // Drop test class dirs; keep the project's own main class dirs.
        if (path.contains("/test") || path.contains("/classes/java/test")) return false
        return path.contains("kuml")
    }

    private fun isAllowedJar(entry: File): Boolean {
        val name = entry.name.lowercase()
        if (!name.endsWith(".jar")) return false
        if (name.startsWith("kuml-")) return true
        return ALLOWED_JAR_PREFIXES.any { name.startsWith(it.lowercase()) }
    }

    /**
     * True if [entry] should be on the curated script-dependency classpath.
     * Package-visible for unit testing without touching the real classpath.
     */
    fun isAllowedEntry(entry: File): Boolean = isAllowedJar(entry) || isAllowedDirectory(entry)

    /**
     * The curated classpath entries, filtered from the current JVM's
     * `java.class.path`. Returns [File]s (existing or not — the compiler tolerates
     * missing entries) for [updateClasspath][kotlin.script.experimental.jvm.updateClasspath].
     */
    fun curatedEntries(): List<File> = curatedFrom(System.getProperty("java.class.path").orEmpty())

    /** Testable core: filters a raw path-separator-joined classpath string. */
    fun curatedFrom(rawClasspath: String): List<File> =
        rawClasspath
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { isAllowedEntry(it) }
            .distinct()
}
