package dev.kuml.cli.plugin

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import dev.kuml.plugin.loader.loader.LoadedPlugin
import dev.kuml.plugin.loader.manifest.ExtensionEntry
import dev.kuml.plugin.loader.manifest.PluginManifest
import dev.kuml.plugin.loader.registry.PluginRegistry
import dev.kuml.plugin.loader.registry.PluginRegistryEntry
import dev.kuml.plugin.loader.registry.PluginRegistryException
import dev.kuml.plugin.loader.registry.PluginRegistryIndex
import dev.kuml.plugin.loader.registry.UpdateCheckService
import dev.kuml.plugin.loader.scan.PluginScanPath
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class PluginCheckUpdatesCommandTest :
    StringSpec({
        // PluginCheckUpdatesCommand calls ensureLoaded(), which scans
        // PluginScanPath.userPluginDir (real ~/.kuml/plugins/ by default) whenever the
        // in-memory registry is empty. Redirect it to a temp dir so a real, leftover
        // fixture JAR from another test class can never leak into this file's runs.
        lateinit var tempPluginsDir: java.nio.file.Path

        beforeSpec {
            tempPluginsDir = Files.createTempDirectory("kuml-plugin-checkupdates-test-")
            tempPluginsDir.toFile().deleteOnExit()
            PluginScanPath.overrideUserPluginDirForTest(tempPluginsDir)
        }

        afterSpec {
            PluginScanPath.clearTestOverride()
        }

        beforeEach { PluginRegistry.clearForTest() }
        afterEach { PluginRegistry.clearForTest() }

        // ── Helpers ───────────────────────────────────────────────────────────

        fun fakeManifest(
            id: String,
            version: String,
        ) = PluginManifest(
            schemaVersion = 1,
            id = id,
            name = "Plugin $id",
            version = version,
            kumlVersionRange = ">=0.1.0",
            extensions = listOf(ExtensionEntry("theme", "test.Impl", id)),
        )

        fun registerFake(
            id: String,
            version: String,
        ) = PluginRegistry.register(LoadedPlugin(fakeManifest(id, version), emptyList(), null))

        fun fakeEntry(
            id: String,
            version: String,
        ) = PluginRegistryEntry(
            id = id,
            category = "theme",
            name = "Plugin $id",
            version = version,
            manifest = "plugins/$id/kuml-plugin.json",
            downloads = "https://plugins.kuml.dev/plugins/$id/releases/latest/$id.jar",
        )

        fun fakeIndex(vararg entries: PluginRegistryEntry) = PluginRegistryIndex(plugins = entries.toList())

        fun serviceReturning(index: PluginRegistryIndex): UpdateCheckService =
            UpdateCheckService(
                indexProvider = { index },
                installedProvider = { PluginRegistry.all() },
            )

        fun serviceUnreachable(): UpdateCheckService =
            UpdateCheckService(
                indexProvider = { throw PluginRegistryException("offline") },
                installedProvider = { PluginRegistry.all() },
            )

        fun commandWith(service: UpdateCheckService) = PluginCheckUpdatesCommand(serviceFactory = { service })

        // ── Exit-code contract ────────────────────────────────────────────────

        "exit 0 when all plugins are up-to-date" {
            registerFake("p.a", "1.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex(fakeEntry("p.a", "1.0.0"))))
            val result = cmd.test("")
            result.statusCode shouldBe 0
        }

        "exit PLUGIN_UPDATES_AVAILABLE (44) when updates available" {
            registerFake("p.b", "1.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex(fakeEntry("p.b", "2.0.0"))))
            val result = cmd.test("")
            result.statusCode shouldBe ExitCodes.PLUGIN_UPDATES_AVAILABLE
        }

        "exit ONLINE_ERROR (1) when registry unreachable" {
            registerFake("p.c", "1.0.0")
            val cmd = commandWith(serviceUnreachable())
            val result = cmd.test("")
            result.statusCode shouldBe ExitCodes.ONLINE_ERROR
        }

        "exit 0 when no plugins installed" {
            // no plugins registered
            val cmd = commandWith(serviceReturning(fakeIndex()))
            val result = cmd.test("")
            result.statusCode shouldBe 0
            result.output shouldContain "No plugins installed"
        }

        // ── Table output ──────────────────────────────────────────────────────

        "table: shows update marker for outdated plugin" {
            registerFake("dev.kuml.theme.pdv", "1.0.0")
            val cmd =
                commandWith(serviceReturning(fakeIndex(fakeEntry("dev.kuml.theme.pdv", "1.2.0"))))
            val result = cmd.test("")
            result.output shouldContain "dev.kuml.theme.pdv"
            result.output shouldContain "update available"
        }

        "table: shows up-to-date marker for current plugin" {
            registerFake("dev.kuml.theme.pdv", "2.0.0")
            val cmd =
                commandWith(serviceReturning(fakeIndex(fakeEntry("dev.kuml.theme.pdv", "2.0.0"))))
            val result = cmd.test("")
            result.output shouldContain "up-to-date"
        }

        "table: shows not-in-registry for plugin absent from registry" {
            registerFake("dev.kuml.orphan", "1.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex()))
            val result = cmd.test("")
            result.output shouldContain "not in registry"
        }

        "table: shows upgrade hint when updates available" {
            registerFake("p.d", "1.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex(fakeEntry("p.d", "1.1.0"))))
            val result = cmd.test("")
            result.output shouldContain "kuml plugin upgrade --all"
        }

        "table: shows all-up-to-date message when nothing to update" {
            registerFake("p.e", "3.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex(fakeEntry("p.e", "3.0.0"))))
            val result = cmd.test("")
            result.output shouldContain "up-to-date"
            result.output shouldNotContain "upgrade"
        }

        // ── JSON contract ─────────────────────────────────────────────────────

        "--json emits registryReachable field" {
            registerFake("p.f", "1.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex(fakeEntry("p.f", "1.0.0"))))
            val result = cmd.test("--json")
            result.output shouldContain "\"registryReachable\":true"
        }

        "--json emits updateCount field" {
            registerFake("p.g", "1.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex(fakeEntry("p.g", "2.0.0"))))
            val result = cmd.test("--json")
            result.output shouldContain "\"updateCount\":1"
        }

        "--json emits plugins array with id, installed, latest, status" {
            registerFake("p.h", "1.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex(fakeEntry("p.h", "1.5.0"))))
            val result = cmd.test("--json")
            result.output shouldContain "\"id\":\"p.h\""
            result.output shouldContain "\"installed\":\"1.0.0\""
            result.output shouldContain "\"latest\":\"1.5.0\""
            result.output shouldContain "\"status\":\"update-available\""
        }

        "--json emits null latest for not-in-registry plugin" {
            registerFake("p.orphan", "1.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex()))
            val result = cmd.test("--json")
            result.output shouldContain "\"latest\":null"
            result.output shouldContain "\"status\":\"not-in-registry\""
        }

        "--json emits error field when registry unreachable" {
            registerFake("p.offline", "1.0.0")
            val cmd = commandWith(serviceUnreachable())
            val result = cmd.test("--json")
            result.output shouldContain "\"registryReachable\":false"
            result.output shouldContain "\"error\":"
        }

        "--json output is single line" {
            registerFake("p.singleline", "1.0.0")
            val cmd = commandWith(serviceReturning(fakeIndex(fakeEntry("p.singleline", "1.0.0"))))
            val result = cmd.test("--json")
            val lines = result.output.trim().lines()
            lines.size shouldBe 1
        }
    })
