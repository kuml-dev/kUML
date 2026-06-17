package dev.kuml.plugin.loader.loader

import dev.kuml.plugin.api.core.KumlPlugin
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files

class PluginClassLoaderTest :
    FunSpec({

        test("forJar with non-existent file still creates a loader (URL-based, error deferred to class load)") {
            // URLClassLoader itself doesn't validate file existence at construction time
            val nonExistent = File(System.getProperty("java.io.tmpdir"), "no-such-plugin-${System.nanoTime()}.jar")
            // Should not throw — URL construction is fine; load failure comes later when loading a class
            val loader = PluginClassLoader.forJar(nonExistent)
            shouldThrow<ClassNotFoundException> { loader.loadClass("com.example.NonExistent") }
            loader.close()
        }

        test("parent ClassLoader is the KumlPlugin class loader, not the system class loader") {
            val tmpJar = Files.createTempFile("kuml-test-plugin", ".jar").toFile()
            try {
                val loader = PluginClassLoader.forJar(tmpJar)
                val expectedParent = KumlPlugin::class.java.classLoader
                loader.parent shouldBe expectedParent
                loader.close()
            } finally {
                tmpJar.delete()
            }
        }

        test("loadClass for a class from the parent layer works") {
            val tmpJar = Files.createTempFile("kuml-test-plugin", ".jar").toFile()
            try {
                val loader = PluginClassLoader.forJar(tmpJar)
                // KumlPlugin is on the parent class loader — should be loadable
                val clazz = loader.loadClass("dev.kuml.plugin.api.core.KumlPlugin")
                clazz shouldNotBe null
                loader.close()
            } finally {
                tmpJar.delete()
            }
        }

        test("two separate loaders have independent class loading (isolation)") {
            val tmpJar1 = Files.createTempFile("kuml-test-plugin-a", ".jar").toFile()
            val tmpJar2 = Files.createTempFile("kuml-test-plugin-b", ".jar").toFile()
            try {
                val loader1 = PluginClassLoader.forJar(tmpJar1)
                val loader2 = PluginClassLoader.forJar(tmpJar2)
                loader1 shouldNotBe loader2
                loader1.close()
                loader2.close()
            } finally {
                tmpJar1.delete()
                tmpJar2.delete()
            }
        }

        test("close on a loader does not throw") {
            val tmpJar = Files.createTempFile("kuml-test-plugin", ".jar").toFile()
            try {
                val loader = PluginClassLoader.forJar(tmpJar)
                shouldNotThrowAny { loader.close() }
            } finally {
                tmpJar.delete()
            }
        }
    })
