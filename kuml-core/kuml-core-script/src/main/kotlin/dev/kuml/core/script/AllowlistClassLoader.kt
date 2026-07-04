package dev.kuml.core.script

/**
 * A **filtering, default-deny** [ClassLoader] used as the *base* (parent)
 * classloader when evaluating an untrusted kUML script inside the sandbox worker
 * child JVM (Welle 7, "layer B" of the MCP-Sandbox architecture).
 *
 * ## Two mechanisms, honestly separated
 *
 * Welle 7 has **two** cooperating parts, and it is important not to over-claim
 * for either:
 *
 *  1. **The curated compilation classpath** ([SandboxClasspath]) — the part that
 *     actually stops **classpath** classes. `wholeClasspath = true` is replaced by
 *     a narrow jar set, so a script that names, say, `com.sun.jna.*` or the Kotlin
 *     compiler's own `org.jetbrains.kotlin.*` gets an *unresolved reference at
 *     compile time*. This is the primary control.
 *  2. **This loader** — the belt-and-braces base-loader filter *behind* the
 *     curated classpath. It refuses any name not on [DEFAULT_ALLOWED_PREFIXES]
 *     with [ClassNotFoundException], catching anything that reaches the parent
 *     delegation despite the curated classpath.
 *
 * It is an **allowlist**: [isAllowed] returns `true` only for names matching one
 * of [allowedPrefixes] (minus the [deniedExactPrefixes] carve-outs); **everything
 * else is denied**. Default-deny is the whole point — an unanticipated dangerous
 * package is denied automatically, not accidentally permitted.
 *
 * ## The hard limit: JDK platform modules are NOT filterable (verified)
 *
 * `java.*` and most `jdk.*` classes live in the **boot module layer**, not on any
 * classpath. The JVM resolves them through the platform/boot loader **without ever
 * consulting a user-defined loader** — an empirically confirmed fact (a script
 * referencing `java.awt.Point` / `java.io.File` / `java.net.Socket` never causes a
 * single `loadClass` call on this loader). So this loader **cannot** hide
 * `java.io`, `java.net`, `java.lang.reflect`, `Runtime`, `ProcessBuilder`, etc.,
 * and does not pretend to — [DEFAULT_ALLOWED_PREFIXES] deliberately allows a broad
 * `java.` so the loader never *lies* about a deny that would never fire, and — more
 * importantly — so that classes the JVM genuinely routes here (e.g.
 * `java.lang.invoke.LambdaMetafactory`, needed by every compiled lambda) are not
 * broken. **Neutralising the *effects* of those JDK classes is the OS cage's job**
 * (Wellen 4-6: `sandbox-exec` / `bwrap` / Job Object). This layer removes only the
 * **non-JDK** (classpath) attack surface a script can name — which is exactly what
 * the curated classpath does, with this loader as backstop.
 *
 * ## Delegation model
 *
 * Wraps a [delegate] (the loader carrying the curated worker classpath). For an
 * **allowed** name it delegates to the parent (normal load, correct class
 * identity). For a **denied** name it throws [ClassNotFoundException] *without*
 * delegating. JDK boot-layer classes never reach here (see above).
 *
 * V0.23.3 — Welle 7.
 */
internal class AllowlistClassLoader(
    private val delegate: ClassLoader,
    private val allowedPrefixes: List<String> = DEFAULT_ALLOWED_PREFIXES,
) : ClassLoader(delegate) {
    /**
     * Names explicitly denied even though their package *prefix* would otherwise
     * be allowed. This is a narrow, surgical carve-out inside an allowed package —
     * e.g. `kotlin.io.*` is broadly harmless DSL/stdlib territory, but the handful
     * of `kotlin.io.*` file/process helpers that wrap `java.io`/`ProcessBuilder`
     * are the exception. Kept tiny and precise: the allowlist does the heavy
     * lifting, this only closes obvious bridges *within* an allowed prefix.
     */
    private val deniedExactPrefixes: List<String> = DEFAULT_DENIED_INNER_PREFIXES

    /**
     * True if [name] (a fully-qualified binary class name) is allowed to load.
     *
     * A name is allowed iff it matches at least one [allowedPrefixes] entry AND
     * matches none of the [deniedExactPrefixes] carve-outs. Bootstrap `java.*`
     * names that we cannot hide are allowed by the allowlist deliberately (see
     * class KDoc) — the deny would never fire against the bootstrap loader anyway.
     */
    fun isAllowed(name: String): Boolean {
        if (deniedExactPrefixes.any { name == it || name.startsWith(it) }) return false
        return allowedPrefixes.any { name == it || name.startsWith(it) }
    }

    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        // Synchronize on the per-name lock the same way the default loadClass does,
        // so parallel-capable loading semantics are preserved.
        synchronized(getClassLoadingLock(name)) {
            // Already loaded by this loader?
            findLoadedClass(name)?.let {
                if (resolve) resolveClass(it)
                return it
            }

            if (!isAllowed(name)) {
                // Default-deny: refuse WITHOUT delegating, so the class is invisible
                // to the compiled script even though it exists on the classpath.
                throw ClassNotFoundException(
                    "kUML sandbox: class '$name' is not on the script allowlist and was denied.",
                )
            }

            // Allowed: delegate to the parent (correct class identity, normal load).
            val loaded = delegate.loadClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    internal companion object {
        /**
         * Package prefixes this loader will delegate. Two categories:
         *
         *  1. **What a legitimate kUML DSL script needs** — derived from
         *     `KumlScript.kt`'s `defaultImports` plus the runtime the constructed
         *     model objects transitively touch:
         *     - `dev.kuml.` — every DSL builder + metamodel type (the whole
         *       legitimate surface: `diagram`, `classOf`, `c4Model`, `sysml2Model`,
         *       `blueprint`, `bpmnModel`, and all model classes they build).
         *     - `kotlin.` — the Kotlin stdlib (collections, sequences, ranges,
         *       `kotlin.reflect` metadata the `@Metadata` machinery reads). Narrowed
         *       by [DEFAULT_DENIED_INNER_PREFIXES].
         *     - `kotlinx.serialization.` — the DSL/metamodel types are
         *       `@Serializable` and touch serializer classes during class init.
         *     - `kotlinx.atomicfu.` — transitive model-layer dep (`atomicfu.atomic`).
         *     - `kotlinx.datetime.` — defensive (value type, no I/O surface).
         *
         *  2. **JDK boot-layer packages we cannot filter anyway** — `java.` and
         *     `jdk.`. As documented in the class KDoc, JVM platform-module classes
         *     are resolved by the boot loader **without consulting this loader**, so
         *     a deny here would never fire; worse, the JVM *does* route a few
         *     genuinely-required classes here (e.g.
         *     `java.lang.invoke.LambdaMetafactory` for every compiled lambda), and
         *     denying those breaks all legitimate scripts. So `java.`/`jdk.` are
         *     allowed honestly, and their *effects* are contained by the OS cage,
         *     not by this loader.
         *
         * A `.`-terminated prefix so `kotlin.` never accidentally matches a rogue
         * `kotlinEvil` top-level package.
         */
        val DEFAULT_ALLOWED_PREFIXES: List<String> =
            listOf(
                "dev.kuml.",
                "kotlin.",
                "kotlinx.serialization.",
                "kotlinx.atomicfu.",
                "kotlinx.datetime.",
                // Boot-layer JDK packages: not filterable by a user loader (see
                // class KDoc). Allowed honestly; contained by the OS cage instead.
                "java.",
                "jdk.",
            )

        /**
         * Surgical denies *inside* the otherwise-allowed `kotlin.` prefix — the
         * stdlib helpers that are thin wrappers over dangerous JDK I/O / process /
         * reflection surface. Allowing the broad `kotlin.` prefix but carving these
         * out keeps the DSL working while removing the obvious stdlib bridges:
         *
         *  - `kotlin.io.` — `File`, `readText`, `ProcessBuilder` extensions, etc.
         *    (A pure DSL script never does file I/O; the *worker itself* does its
         *    temp-file writes in worker code on the app classloader, not through
         *    the script's base loader, so denying `kotlin.io.` here does not break
         *    the worker's own temp handling.)
         *  - `kotlin.concurrent.` — thread helpers.
         *  - `kotlin.system.` — `exitProcess`, `measureTimeMillis`.
         *  - `kotlin.reflect.full.` / `kotlin.reflect.jvm.` — the *heavy* reflection
         *    API (`kotlin.reflect.` core metadata stays allowed — the `@Metadata`
         *    reader + basic KClass need it — but the full/jvm reflection bridges
         *    that can reach arbitrary members are denied).
         *
         * NOTE: these `kotlin.*` helpers are on the *classpath* (kotlin-stdlib jar),
         * so — unlike JDK boot classes — a deny here CAN fire when the compiled
         * script routes through this loader. They are a genuine, working carve-out.
         */
        val DEFAULT_DENIED_INNER_PREFIXES: List<String> =
            listOf(
                "kotlin.io.",
                "kotlin.concurrent.",
                "kotlin.system.",
                "kotlin.reflect.full.",
                "kotlin.reflect.jvm.",
            )
    }
}
