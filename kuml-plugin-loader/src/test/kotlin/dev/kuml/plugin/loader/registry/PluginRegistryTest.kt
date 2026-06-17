package dev.kuml.plugin.loader.registry

import dev.kuml.plugin.loader.loader.LoadedPlugin
import dev.kuml.plugin.loader.manifest.ExtensionEntry
import dev.kuml.plugin.loader.manifest.PluginManifest
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PluginRegistryTest :
    FunSpec({

        beforeEach { PluginRegistry.clearForTest() }

        fun makeLoaded(id: String) =
            LoadedPlugin(
                manifest =
                    PluginManifest(
                        id = id,
                        name = "Test Plugin $id",
                        version = "1.0.0",
                        kumlVersionRange = ">=3.0.0",
                        extensions = listOf(ExtensionEntry("theme", "com.example.ThemePlugin", "$id-ext")),
                    ),
                plugins = emptyList(),
                classLoader = null,
            )

        test("register and get returns the loaded plugin") {
            val plugin = makeLoaded("p1")
            PluginRegistry.register(plugin)
            PluginRegistry.get("p1") shouldBe plugin
        }

        test("get returns null for unknown id") {
            PluginRegistry.get("unknown") shouldBe null
        }

        test("all returns snapshot of all registered plugins") {
            PluginRegistry.register(makeLoaded("a"))
            PluginRegistry.register(makeLoaded("b"))
            PluginRegistry.all() shouldHaveSize 2
        }

        test("second register with same id overwrites first") {
            val first = makeLoaded("dup")
            val second = makeLoaded("dup")
            PluginRegistry.register(first)
            PluginRegistry.register(second)
            PluginRegistry.all() shouldHaveSize 1
            PluginRegistry.get("dup") shouldBe second
        }

        test("unload removes plugin and returns true") {
            PluginRegistry.register(makeLoaded("removable"))
            val result = PluginRegistry.unload("removable")
            result shouldBe true
            PluginRegistry.get("removable") shouldBe null
        }

        test("unload for unknown id returns false") {
            val result = PluginRegistry.unload("ghost")
            result shouldBe false
        }

        test("clearForTest empties the registry") {
            PluginRegistry.register(makeLoaded("x"))
            PluginRegistry.register(makeLoaded("y"))
            PluginRegistry.clearForTest()
            PluginRegistry.all().shouldHaveSize(0)
        }

        test("concurrent register and get does not throw") {
            val executor = Executors.newFixedThreadPool(4)
            shouldNotThrowAny {
                repeat(20) { i ->
                    executor.submit {
                        PluginRegistry.register(makeLoaded("concurrent-$i"))
                        PluginRegistry.get("concurrent-$i")
                        PluginRegistry.all()
                    }
                }
                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
            PluginRegistry.all() shouldNotBe null
        }
    })
