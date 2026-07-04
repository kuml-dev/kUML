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
    // Hard upper bound on the size of a submitted script. Legitimate kUML DSL
    // scripts are a few KiB at most; a multi-MiB payload is either abusive or an
    // attempt to exhaust the compiler. Rejected before any regex scan or eval.
    const val MAX_SCRIPT_LENGTH: Int = 256 * 1024 // 256 KiB

    /**
     * Validates that [script] does not contain patterns indicative of arbitrary
     * code execution attempts.
     *
     * @throws ScriptSecurityException if a forbidden pattern is detected.
     */
    fun validate(script: String) {
        if (script.length > MAX_SCRIPT_LENGTH) {
            throw ScriptSecurityException(
                "kUML script rejected: exceeds maximum length of $MAX_SCRIPT_LENGTH characters.",
            )
        }
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
            // Additional file I/O primitives (readText/writeText alone were insufficient)
            Regex("""(?i)\breadBytes\s*\(""") to "readBytes() file read call",
            Regex("""(?i)\breadLines\s*\(""") to "readLines() file read call",
            Regex("""(?i)\bappendText\s*\(""") to "appendText() file write call",
            Regex("""(?i)\bappendBytes\s*\(""") to "appendBytes() file write call",
            Regex("""(?i)\bwriteBytes\s*\(""") to "writeBytes() file write call",
            Regex("""(?i)\bbufferedReader\s*\(""") to "bufferedReader() call",
            Regex("""(?i)\bbufferedWriter\s*\(""") to "bufferedWriter() call",
            Regex("""(?i)\bprintWriter\s*\(""") to "printWriter() call",
            Regex("""(?i)\binputStream\b""") to "inputStream reference",
            Regex("""(?i)\boutputStream\b""") to "outputStream reference",
            // Reflection / classloading
            Regex("""(?i)\bClass\.forName\s*\(""") to "Class.forName() call",
            Regex("""(?i)\bclassLoader\b""") to "classLoader reference",
            Regex("""(?i)\bgetDeclaredMethod\s*\(""") to "getDeclaredMethod() reflection call",
            Regex("""(?i)\bgetDeclaredMethods\s*\(""") to "getDeclaredMethods() reflection call",
            Regex("""(?i)\bgetMethod\s*\(""") to "getMethod() reflection call",
            Regex("""(?i)\bgetMethods\s*\(""") to "getMethods() reflection call",
            Regex("""(?i)\bgetDeclaredField\s*\(""") to "getDeclaredField() reflection call",
            Regex("""(?i)\bgetDeclaredFields\s*\(""") to "getDeclaredFields() reflection call",
            Regex("""(?i)\bgetField\s*\(""") to "getField() reflection call",
            Regex("""(?i)\bgetDeclaredConstructor""") to "getDeclaredConstructor() reflection call",
            Regex("""(?i)\bgetConstructor""") to "getConstructor() reflection call",
            Regex("""(?i)\bnewInstance\s*\(""") to "newInstance() reflection call",
            Regex("""(?i)\.\s*invoke\s*\(""") to "reflective invoke() call",
            Regex("""(?i)\bsetAccessible\s*\(""") to "setAccessible() reflection call",
            Regex("""(?i)\bisAccessible\b""") to "isAccessible reflection reference",
            Regex("""(?i)\bloadClass\s*\(""") to "loadClass() call",
            Regex("""(?i)\bMethodHandles\b""") to "MethodHandles reference",
            Regex("""(?i)\bKCallable\b""") to "KCallable reflection reference",
            Regex("""(?i)::\s*class\s*\.\s*java\b""") to "::class.java reflection bridge",
            // Threading — matches Thread(...), Thread { ... } (trailing lambda), and Thread.foo()
            Regex("""(?i)\bThread\s*[({.]""") to "Thread reference",
            // System-level access
            Regex("""(?i)\bSystem\s*\.\s*exit\b""") to "System.exit() call",
            Regex("""(?i)\bexitProcess\s*\(""") to "kotlin.system.exitProcess() call",
            Regex("""(?i)\bSystem\s*\.\s*getenv\b""") to "System.getenv() call",
            Regex("""(?i)\bSystem\s*\.\s*getProperty\b""") to "System.getProperty() call",
            Regex("""(?i)\bSystem\s*\.\s*getProperties\b""") to "System.getProperties() call",
            Regex("""(?i)\bSystem\s*\.\s*setProperty\b""") to "System.setProperty() call",
            Regex("""(?i)\bkotlin\.system\b""") to "kotlin.system reference",
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
