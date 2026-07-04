package dev.kuml.core.script

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * Welle-7 (layer B) unit tests for the [AllowlistClassLoader] — the belt-and-braces
 * filtering, default-deny base classloader behind the curated classpath.
 *
 * These tests prove the loader's **policy** directly: an allowlisted (or
 * unfilterable JDK) name delegates and loads; a non-allowlisted **classpath** name
 * is refused with [ClassNotFoundException] *without* delegating.
 *
 * ## What this layer honestly does and does not block
 *
 * The loader filters **classpath** classes (JNA, the Kotlin compiler, unanticipated
 * third-party packages). It **cannot** filter JDK boot-layer classes (`java.*`,
 * `jdk.*`) — the JVM resolves those through the boot loader without consulting a
 * user loader (empirically confirmed), and denying `java.lang.invoke.*` would even
 * break every compiled lambda. So `isAllowed` returns `true` for `java.*` honestly;
 * neutralising their *effects* is the OS cage's job (Wellen 4-6). The end-to-end
 * proof that a script cannot *name* a classpath class lives in
 * [AllowlistScriptEvaluationTest] (via the curated classpath, this loader's twin).
 *
 * V0.23.3 — Welle 7.
 */
class AllowlistClassLoaderTest :
    FunSpec({

        val loader = AllowlistClassLoader(AllowlistClassLoaderTest::class.java.classLoader)

        context("isAllowed — default-DENY over CLASSPATH classes (not a denylist)") {
            test("allowed: legitimate kUML DSL / stdlib / serialization packages") {
                loader.isAllowed("dev.kuml.core.model.KumlDiagram").shouldBeTrue()
                loader.isAllowed("dev.kuml.uml.UmlClass").shouldBeTrue()
                loader.isAllowed("kotlin.collections.CollectionsKt").shouldBeTrue()
                loader.isAllowed("kotlinx.serialization.KSerializer").shouldBeTrue()
            }

            test("denied: dangerous CLASSPATH packages a legitimate DSL script never needs") {
                // These are on the worker classpath (jars), NOT JDK boot modules —
                // so this loader can and does refuse them.
                loader.isAllowed("com.sun.jna.Native").shouldBeFalse()
                loader.isAllowed("org.jetbrains.kotlin.cli.common.CLITool").shouldBeFalse()
                loader.isAllowed("kotlinx.coroutines.BuildersKt").shouldBeFalse()
                loader.isAllowed("io.kotest.matchers.MatchersKt").shouldBeFalse()
            }

            test("denied: an unanticipated new package is denied by DEFAULT (the allowlist property)") {
                // Neither on the allowlist nor a JDK module — proving the direction
                // is default-deny, not default-allow-with-exceptions.
                loader.isAllowed("org.attacker.Payload").shouldBeFalse()
                loader.isAllowed("net.evil.Exfil").shouldBeFalse()
            }

            test("denied: stdlib bridges INSIDE the allowed kotlin. prefix (surgical carve-outs)") {
                // kotlin.* is on the classpath (kotlin-stdlib jar), so these DENIES
                // genuinely fire when the script routes through this loader.
                loader.isAllowed("kotlin.io.FilesKt").shouldBeFalse()
                loader.isAllowed("kotlin.io.path.PathsKt").shouldBeFalse()
                loader.isAllowed("kotlin.concurrent.ThreadsKt").shouldBeFalse()
                loader.isAllowed("kotlin.system.ProcessKt").shouldBeFalse()
                loader.isAllowed("kotlin.reflect.full.KClasses").shouldBeFalse()
                loader.isAllowed("kotlin.reflect.jvm.ReflectJvmMapping").shouldBeFalse()
                // …while core kotlin.reflect metadata stays allowed (KClass etc.).
                loader.isAllowed("kotlin.reflect.KClass").shouldBeTrue()
            }

            test("prefix boundary: 'kotlin.' must not match a rogue 'kotlinEvil' top package") {
                loader.isAllowed("kotlinEvil.Payload").shouldBeFalse()
            }

            test("honesty: JDK boot-layer packages are ALLOWED (cannot be filtered by a user loader)") {
                // Denying these would be a lie (the boot loader answers first) and
                // would break legit code (e.g. java.lang.invoke for every lambda).
                // Their EFFECTS are contained by the OS cage, not here.
                loader.isAllowed("java.lang.System").shouldBeTrue()
                loader.isAllowed("java.lang.Runtime").shouldBeTrue()
                loader.isAllowed("java.lang.ProcessBuilder").shouldBeTrue()
                loader.isAllowed("java.io.File").shouldBeTrue()
                loader.isAllowed("java.net.Socket").shouldBeTrue()
                loader.isAllowed("java.lang.invoke.LambdaMetafactory").shouldBeTrue()
            }
        }

        context("loadClass — enforcement behaviour") {
            test("an allowed class loads (delegated to the parent, correct identity)") {
                val c = loader.loadClass("dev.kuml.core.model.KumlDiagram")
                c.name shouldBe "dev.kuml.core.model.KumlDiagram"
            }

            test("a denied CLASSPATH class throws ClassNotFoundException even though it is on the classpath") {
                // JNA and the Kotlin compiler are unquestionably on the worker
                // classpath, yet the filtering loader refuses them WITHOUT delegating.
                shouldThrow<ClassNotFoundException> {
                    loader.loadClass("com.sun.jna.Native")
                }
                shouldThrow<ClassNotFoundException> {
                    loader.loadClass("kotlinx.coroutines.BuildersKt")
                }
            }
        }
    })
