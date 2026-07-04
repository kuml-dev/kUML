package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.File
import java.nio.file.Files
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Welle-7 (layer B) **end-to-end** proof that the sandbox evaluation path (curated
 * classpath + [AllowlistClassLoader] base loader) blocks a script from *naming* a
 * dangerous classpath class, while every legitimate DSL script still runs.
 *
 * ## Denylist-independent, like the OS-sandbox tests
 *
 * The regex [KumlScriptGuard] (layer 1) already blocks `java.net`, `ProcessBuilder`,
 * `File(`, reflection, etc. at the *text* level, so a script that tries those is
 * rejected before the compiler runs — which would make it ambiguous *which* layer
 * stopped it. To prove the **Welle-7** layer specifically, these tests:
 *
 *  1. Call [KumlScriptHost.eval] **directly** (bypassing [ScriptEvaluationCore],
 *     hence bypassing the guard) — exactly the way [OsSandboxTest] launches a raw
 *     Java escape program to bypass the guard for the OS layer.
 *  2. Reference a **classpath** class that is **not on the curated classpath and
 *     not matched by any guard regex** — `com.sun.jna.Pointer` (the JNA jar is on
 *     the worker classpath but excluded from the curated set). A rejection can only
 *     come from the curated-classpath narrowing, never from the denylist.
 *
 * NOTE on the honest limit: a **JDK boot-layer** class (`java.awt.Point`,
 * `java.io.File`, …) is deliberately NOT used as the probe, because those are NOT
 * blockable by this layer (boot module layer, not on any classpath — see
 * [AllowlistClassLoader] / [SandboxClasspath] KDoc). Their effects are the OS
 * cage's responsibility. JNA is the clean, honest probe for what Welle 7 *can* do.
 *
 * V0.23.3 — Welle 7.
 */
class AllowlistScriptEvaluationTest :
    FunSpec({

        fun writeScript(source: String): File {
            val f = Files.createTempFile("kuml-allowlist-test-", ".kuml.kts").toFile()
            f.writeText(source)
            f.deleteOnExit()
            return f
        }

        fun hasErrors(result: ResultWithDiagnostics<*>): Boolean =
            result is ResultWithDiagnostics.Failure ||
                result.reports.any { it.severity == ScriptDiagnostic.Severity.ERROR }

        fun allowlistLoader(): ClassLoader = AllowlistClassLoader(AllowlistScriptEvaluationTest::class.java.classLoader)

        val legitScript =
            """
            diagram(name = "Shop", type = DiagramType.CLASS) {
                classOf("Order")
                classOf("Customer")
            }
            """.trimIndent()

        // References a CLASSPATH class (JNA) NOT on the curated classpath and NOT
        // caught by any guard regex. The only thing that can stop it is the
        // Welle-7 curated-classpath narrowing.
        val deniedClassScript =
            """
            val p = com.sun.jna.Pointer(0L)
            diagram(name = "x", type = DiagramType.CLASS) {}
            """.trimIndent()

        test("legitimate DSL script evaluates cleanly UNDER the allowlist (no false-positive)") {
            // This is the single most important acceptance test of Welle 7: the
            // allowlist must not break real scripts. If dev.kuml.* / kotlin.* /
            // kotlinx.serialization.* were incompletely allowed, this would fail.
            val result = KumlScriptHost.eval(writeScript(legitScript), allowlistLoader())
            hasErrors(result).shouldBeFalse()
        }

        test("a script referencing a NON-curated classpath class is blocked (unresolved reference)") {
            val result = KumlScriptHost.eval(writeScript(deniedClassScript), allowlistLoader())
            hasErrors(result).shouldBeTrue()
        }

        test("control: the SAME denied-class script compiles fine WITHOUT the sandbox (full classpath)") {
            // Proves the failure above is the curated classpath hiding com.sun.jna,
            // not a syntax error in the script itself. With wholeClasspath=true JNA
            // resolves and the script compiles/runs to completion.
            val result = KumlScriptHost.eval(writeScript(deniedClassScript), evaluationClassLoader = null)
            hasErrors(result).shouldBeFalse()
        }

        context("full worker-path parity: legitimate scripts of every DSL family pass under the allowlist") {
            // The worker enforces the allowlist by default; these mirror the
            // families a real MCP user renders (UML / C4 / SysML2 / BPMN / Blueprint).
            val families: Map<String, String> =
                mapOf(
                    "UML class" to
                        """diagram(name = "U", type = DiagramType.CLASS) { classOf("A"); classOf("B") }""",
                    "C4 model" to
                        """
                        c4Model(name = "Bank") {
                            val u = person("Customer")
                            val s = softwareSystem("Core")
                            systemContextDiagram(name = "Ctx") { include(u); include(s) }
                        }
                        """.trimIndent(),
                    "SysML2 STM" to
                        """
                        import dev.kuml.sysml2.dsl.sysml2Model
                        sysml2Model("Light") {
                            val i = stateDef("Initial", isInitial = true)
                            val r = stateDef("Red")
                            transition("t", i, r)
                            stmDiagram("STM") { include(i); include(r) }
                        }
                        """.trimIndent(),
                )

            families.forEach { (name, src) ->
                test("[$name] renders under the allowlist without a false-positive block") {
                    val result = KumlScriptHost.eval(writeScript(src), allowlistLoader())
                    hasErrors(result).shouldBeFalse()
                }
            }
        }

        context("worker-only scope: the allowlist policy flag") {
            test("policy defaults to enforced; only 'disabled' opts out") {
                WorkerClassLoaderPolicy.enforcedFor(null).shouldBeTrue()
                WorkerClassLoaderPolicy.enforcedFor("enforced").shouldBeTrue()
                WorkerClassLoaderPolicy.enforcedFor("ENFORCED").shouldBeTrue()
                WorkerClassLoaderPolicy.enforcedFor("nonsense").shouldBeTrue() // fail-safe
                WorkerClassLoaderPolicy.enforcedFor("disabled").shouldBeFalse()
                WorkerClassLoaderPolicy.enforcedFor(" disabled ").shouldBeFalse()
            }

            test("evaluationClassLoader is an AllowlistClassLoader when enforced, null when disabled") {
                (WorkerClassLoaderPolicy.evaluationClassLoader(enforced = true) is AllowlistClassLoader)
                    .shouldBeTrue()
                (WorkerClassLoaderPolicy.evaluationClassLoader(enforced = false) == null).shouldBeTrue()
            }
        }
    })
