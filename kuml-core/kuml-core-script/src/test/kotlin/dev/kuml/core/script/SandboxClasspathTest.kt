package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle-7 (layer B) unit tests for [SandboxClasspath] — the **curated dependency
 * classpath** filter, the mechanism that actually stops a script from *naming* a
 * dangerous classpath class (JNA, the Kotlin compiler, coroutines, …).
 *
 * Pure and OS-independent: it filters a path string, so it runs anywhere with no
 * child JVM. The end-to-end behavioural proof (a JNA reference becomes an
 * unresolved-reference compile error) is in [AllowlistScriptEvaluationTest].
 *
 * V0.23.3 — Welle 7.
 */
class SandboxClasspathTest :
    FunSpec({

        val sep = File.pathSeparatorChar

        test("keeps kuml-* module jars and DSL runtime essentials") {
            SandboxClasspath.isAllowedEntry(File("/x/kuml-core-model-jvm-0.23.2.jar")) shouldBe true
            SandboxClasspath.isAllowedEntry(File("/x/kuml-metamodel-uml-jvm-0.23.2.jar")) shouldBe true
            SandboxClasspath.isAllowedEntry(File("/x/kotlin-stdlib-2.4.0.jar")) shouldBe true
            SandboxClasspath.isAllowedEntry(File("/x/kotlin-reflect-2.4.0.jar")) shouldBe true
            SandboxClasspath.isAllowedEntry(File("/x/kotlinx-serialization-core-jvm-1.11.0.jar")) shouldBe true
            SandboxClasspath.isAllowedEntry(File("/x/kotlinx-serialization-json-jvm-1.11.0.jar")) shouldBe true
            SandboxClasspath.isAllowedEntry(File("/x/kotlinx-io-core-jvm-0.8.2.jar")) shouldBe true
            SandboxClasspath.isAllowedEntry(File("/x/atomicfu-jvm-0.33.0.jar")) shouldBe true
            SandboxClasspath.isAllowedEntry(File("/x/annotations-13.0.jar")) shouldBe true
        }

        test("drops dangerous / unnecessary classpath jars") {
            SandboxClasspath.isAllowedEntry(File("/x/jna-5.19.1.jar")) shouldBe false
            SandboxClasspath.isAllowedEntry(File("/x/jna-platform-5.19.1.jar")) shouldBe false
            SandboxClasspath.isAllowedEntry(File("/x/kotlin-compiler-embeddable-2.4.0.jar")) shouldBe false
            SandboxClasspath.isAllowedEntry(File("/x/kotlin-scripting-jvm-2.4.0.jar")) shouldBe false
            SandboxClasspath.isAllowedEntry(File("/x/kotlinx-coroutines-core-jvm-1.10.2.jar")) shouldBe false
            SandboxClasspath.isAllowedEntry(File("/x/kotlinx-coroutines-debug-1.10.2.jar")) shouldBe false
            SandboxClasspath.isAllowedEntry(File("/x/kotest-runner-junit5-jvm-5.0.0.jar")) shouldBe false
            SandboxClasspath.isAllowedEntry(File("/x/some-random-third-party.jar")) shouldBe false
        }

        test("curatedFrom filters a full classpath string end-to-end") {
            val raw =
                listOf(
                    "/repo/kuml-core-model-jvm-0.23.2.jar",
                    "/repo/kotlin-stdlib-2.4.0.jar",
                    "/repo/jna-5.19.1.jar",
                    "/repo/kotlin-compiler-embeddable-2.4.0.jar",
                    "/repo/kotlinx-serialization-json-jvm-1.11.0.jar",
                ).joinToString(sep.toString())

            val curated = SandboxClasspath.curatedFrom(raw).map { it.name }
            curated shouldContain "kuml-core-model-jvm-0.23.2.jar"
            curated shouldContain "kotlin-stdlib-2.4.0.jar"
            curated shouldContain "kotlinx-serialization-json-jvm-1.11.0.jar"
            curated shouldNotContain "jna-5.19.1.jar"
            curated shouldNotContain "kotlin-compiler-embeddable-2.4.0.jar"
        }

        test("blank entries are ignored") {
            SandboxClasspath.curatedFrom("${sep}$sep/x/kotlin-stdlib-2.4.0.jar$sep").map { it.name } shouldBe
                listOf("kotlin-stdlib-2.4.0.jar")
        }
    })
