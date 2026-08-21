package dev.kuml.desktop.ai

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext

/**
 * V3.7.1 — Regression guard for the AI panel's Main-dispatcher crash.
 *
 * Root cause: [dev.kuml.desktop.ai.AiPanelState] uses `withContext(Dispatchers.Main)` at
 * nine call sites, but `kuml-desktop`'s runtime classpath never declared
 * `kotlinx-coroutines-swing` — the module that registers a `MainDispatcherFactory` via
 * `META-INF/services`. Compose Desktop ships its own internal UI dispatcher but does NOT
 * register one for kotlinx-coroutines' `Dispatchers.Main`, so every `withContext(Dispatchers.Main)`
 * threw `IllegalStateException: Module with the Main dispatcher is missing` — i.e. every
 * message sent to the AI panel crashed it.
 *
 * Two complementary assertions, both must stay green:
 *  - The classpath-structural one below is authoritative and immune to `Dispatchers.setMain`
 *    leakage from other test specs (S2/S3 in the V3.7.1 plan) — it inspects the actual
 *    META-INF/services registration on the real jvmTest runtime classpath.
 *  - The dispatcher-usability one is a behavioural cross-check. Note `kotlinx-coroutines-test`
 *    is always on the test classpath and installs a `TestMainDispatcher` with
 *    `Int.MAX_VALUE` priority that delegates to the real Main dispatcher — so this test
 *    exercises the delegation path, not a raw `Dispatchers.Main` access.
 *
 * Deliberately does NOT wrap `Dispatchers.Main` in `withContext { }` — that would start the
 * AWT event thread inside the test JVM. `isDispatchNeeded` is sufficient: it throws through
 * `MissingMainCoroutineDispatcher` when no factory is registered, and does not throw once
 * `SwingDispatcherFactory` (registered by kotlinx-coroutines-swing) is on the classpath.
 */
class MainDispatcherAvailabilityTest :
    FunSpec({

        test("kotlinx-coroutines-swing is on the runtime classpath (registers the Main dispatcher)") {
            val services =
                MainDispatcherAvailabilityTest::class.java.classLoader
                    .getResources("META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory")
                    .toList()
                    .joinToString("\n") { url -> url.openStream().bufferedReader().use { it.readText() } }
            services shouldContain "kotlinx.coroutines.swing.SwingDispatcherFactory"
        }

        test("Dispatchers.Main is usable without an installed test dispatcher") {
            shouldNotThrowAny { Dispatchers.Main.isDispatchNeeded(EmptyCoroutineContext) }
        }
    })
