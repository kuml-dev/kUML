package dev.kuml.core.script

/**
 * Static allow-list guard for kUML DSL scripts submitted via untrusted channels
 * (MCP tools, REST API, etc.).
 *
 * **Threat model**: an MCP client (or any caller that can supply a raw `script`
 * string) could submit arbitrary Kotlin code — `ProcessBuilder`, `File.writeText`,
 * `Runtime.exec`, network calls, reflection to reach private fields, etc. —
 * instead of a well-formed kUML DSL script. Because [KumlScriptHost] evaluates
 * scripts with `wholeClasspath = true` and no classloader isolation, any Kotlin
 * code compiles and runs with full JVM privileges.
 *
 * **Mitigation**: before eval, this guard scans the raw source text for patterns
 * that have no legitimate use in a kUML DSL script but are required for
 * dangerous operations. Rejection is fast (pure-Kotlin regex) and happens before
 * the Kotlin compiler is invoked.
 *
 * **Scope**: this guard is one defence-in-depth layer, not a complete sandbox.
 * The underlying JVM grants the script process-level access; full sandboxing
 * would require a separate classloader + SecurityManager replacement (Project
 * Loom virtual-thread isolation or GraalVM Polyglot context). This guard
 * eliminates trivial exploitation vectors with near-zero false-positive rate on
 * legitimate DSL scripts.
 *
 * V3.1.23
 */
object KumlScriptGuard {
    /**
     * Validates that [script] does not contain patterns indicative of arbitrary
     * code execution attempts.
     *
     * @throws ScriptSecurityException if a forbidden pattern is detected.
     */
    fun validate(script: String) {
        FORBIDDEN_PATTERNS.forEach { (pattern, reason) ->
            if (pattern.containsMatchIn(script)) {
                throw ScriptSecurityException(
                    "kUML script rejected: $reason. " +
                        "Only kUML DSL constructs are permitted in scripts submitted via MCP or API.",
                )
            }
        }
    }

    // ── Forbidden pattern catalogue ──────────────────────────────────────────

    /**
     * Each entry is a (Regex, human-readable reason) pair.
     *
     * Patterns are written to minimise false positives on legitimate kUML DSL:
     * - kUML identifiers do not reference `java.io`, `java.lang.Runtime`,
     *   `ProcessBuilder`, `java.net`, `kotlin.io`, or reflection APIs.
     * - String literals in DSL scripts are diagram/element names — they never
     *   contain class-path-style dot-paths or system property keys.
     */
    private val FORBIDDEN_PATTERNS: List<Pair<Regex, String>> =
        listOf(
            // File-system I/O
            Regex("""(?i)\bjava\.io\b""") to "java.io reference",
            Regex("""(?i)\bkotlin\.io\b""") to "kotlin.io reference",
            Regex("""(?i)\bjava\.nio\b""") to "java.nio reference",
            Regex("""(?i)\bFile\s*\(""") to "File constructor call",
            Regex("""(?i)\bFiles\s*\.""") to "java.nio.file.Files call",
            Regex("""(?i)\bPaths\s*\.""") to "java.nio.file.Paths call",
            Regex("""(?i)\breadText\s*\(""") to "readText() file read call",
            Regex("""(?i)\bwriteText\s*\(""") to "writeText() file write call",
            Regex("""(?i)\bdeleteRecursively\s*\(""") to "deleteRecursively() call",
            // Process execution
            Regex("""(?i)\bProcessBuilder\b""") to "ProcessBuilder reference",
            Regex("""(?i)\bRuntime\s*\.\s*getRuntime\b""") to "Runtime.getRuntime() call",
            Regex("""(?i)\bexec\s*\(""") to "exec() call",
            // Network I/O
            Regex("""(?i)\bjava\.net\b""") to "java.net reference",
            Regex("""(?i)\bURL\s*\(""") to "URL constructor call",
            Regex("""(?i)\bHttpClient\b""") to "HttpClient reference",
            Regex("""(?i)\bURLConnection\b""") to "URLConnection reference",
            Regex("""(?i)\bSocket\s*\(""") to "Socket constructor call",
            // Reflection / classloading
            Regex("""(?i)\bClass\.forName\s*\(""") to "Class.forName() call",
            Regex("""(?i)\bclassLoader\b""") to "classLoader reference",
            Regex("""(?i)\bgetDeclaredMethod\s*\(""") to "getDeclaredMethod() reflection call",
            Regex("""(?i)\bgetDeclaredField\s*\(""") to "getDeclaredField() reflection call",
            Regex("""(?i)\bsetAccessible\s*\(""") to "setAccessible() reflection call",
            // System-level access
            Regex("""(?i)\bSystem\s*\.\s*exit\b""") to "System.exit() call",
            Regex("""(?i)\bSystem\s*\.\s*getenv\b""") to "System.getenv() call",
            Regex("""(?i)\bSystem\s*\.\s*getProperty\b""") to "System.getProperty() call",
            Regex("""(?i)\bSystem\s*\.\s*setProperty\b""") to "System.setProperty() call",
            // Dynamic code loading
            Regex("""(?i)\beval\s*\(""") to "eval() call",
            Regex("""(?i)\bgroovy\b""") to "Groovy scripting reference",
            Regex("""(?i)\bScriptEngine\b""") to "ScriptEngine reference",
        )
}

/**
 * Thrown by [KumlScriptGuard.validate] when a submitted script contains a
 * pattern that indicates an arbitrary code execution attempt.
 */
class ScriptSecurityException(
    message: String,
) : SecurityException(message)
