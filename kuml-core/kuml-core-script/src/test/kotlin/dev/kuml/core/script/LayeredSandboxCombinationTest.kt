package dev.kuml.core.script

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Welle-7 **combination** test: confirms the three script-sandbox layers
 * coexist and cooperate through the real worker path, and that legitimate scripts
 * pass through **all three at once** while hostile ones are stopped.
 *
 * Layers exercised together (all at their secure defaults on this macOS machine):
 *
 *  1. **Layer 1 — regex denylist** ([KumlScriptGuard], Welle 1): runs in the pool
 *     parent before a worker is even spent, and again inside the worker.
 *  2. **Layer OS — `sandbox-exec` cage** (Welle 4): the pool launches every worker
 *     through [OsSandbox.wrap]; on macOS the isolation mode defaults to `required`.
 *  3. **Layer B — curated classpath + [AllowlistClassLoader]** (Welle 7): the
 *     worker evaluates with the narrowed classpath + allowlist base loader by
 *     default (`KUML_MCP_SANDBOX_CLASSLOADER` unset ⇒ enforced).
 *
 * The single most important assertion of the whole wave is that these do **not**
 * interfere with each other for legitimate DSL scripts — a false-positive block
 * would make the feature unusable. This test runs a real [WorkerPool] (child JVMs,
 * OS cage, allowlist) and renders one script of each major DSL family end-to-end.
 *
 * This test is macOS-behavioural (the dev machine): on macOS the OS cage is
 * genuinely applied, so a green run proves the compiler warm-up, temp writes, and
 * model construction all survive the combined cage + curated classpath + allowlist.
 *
 * V0.23.3 — Welle 7.
 */
class LayeredSandboxCombinationTest :
    FunSpec({

        val isMac =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .let { it.contains("mac") || it.contains("darwin") }

        test("all three layers are active by default (sanity: defaults are the secure ones)") {
            // Layer 1 always runs. Layer 7 default = enforced. Layer OS default on
            // macOS = required. Assert the *policy* is the secure default so the
            // behavioural run below is genuinely exercising all three.
            WorkerClassLoaderPolicy.enforcedFor(null).shouldBeTrue() // Welle 7 enforced
            if (isMac) {
                OsSandbox.modeFrom(null, OsSandbox.Platform.MAC) shouldBe OsSandbox.Mode.REQUIRED
                OsSandbox.isolationAvailable().shouldBeTrue() // sandbox-exec present
            }
        }

        test("legitimate scripts of every DSL family render through denylist + OS cage + allowlist together") {
            val pool = WorkerPool(poolSize = 2, maxConcurrentWorkers = 4)
            try {
                val families: Map<String, String> =
                    mapOf(
                        "UML" to
                            """diagram(name = "U", type = DiagramType.CLASS) { classOf("Order"); classOf("Customer") }""",
                        "C4" to
                            """
                            c4Model(name = "Bank") {
                                val u = person("Customer")
                                val s = softwareSystem("Core")
                                systemContextDiagram(name = "Ctx") { include(u); include(s) }
                            }
                            """.trimIndent(),
                        "SysML2-STM" to
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
                    withClue(name) {
                        val result = pool.evaluate(src)
                        result.shouldBeInstanceOf<EvaluatedScript.Success>()
                    }
                }
            } finally {
                pool.close()
            }
        }

        test("through the full worker path, a hostile script is still rejected (denylist short-circuit)") {
            // A ProcessBuilder attempt is caught by layer 1 before a worker is spent
            // — proving the layers compose without the allowlist/OS cage masking or
            // weakening the cheap first filter.
            val pool = WorkerPool(poolSize = 1, maxConcurrentWorkers = 2)
            try {
                val hostile = """diagram(name = "x", type = DiagramType.CLASS) {}; ProcessBuilder("id").start()"""
                val result = pool.evaluate(hostile)
                val failure = result.shouldBeInstanceOf<EvaluatedScript.Failure>()
                failure.kind shouldBe FailureKind.GUARD
            } finally {
                pool.close()
            }
        }
    })
