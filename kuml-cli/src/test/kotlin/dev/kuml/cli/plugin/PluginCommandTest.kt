package dev.kuml.cli.plugin

import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import dev.kuml.plugin.loader.loader.LoadedPlugin
import dev.kuml.plugin.loader.manifest.ExtensionEntry
import dev.kuml.plugin.loader.manifest.PluginManifest
import dev.kuml.plugin.loader.registry.PluginRegistry
import dev.kuml.plugin.loader.registry.PluginRegistryClient
import dev.kuml.plugin.loader.scan.PluginScanPath
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginCommandTest :
    StringSpec({
        // PluginInstallCommand/PluginRemoveCommand read/write PluginScanPath.userPluginDir
        // for real (see PluginCommand.kt) — redirect it to a temp dir so these tests never
        // touch the developer's actual ~/.kuml/plugins/. Same pattern as
        // PluginUpgradeCommandTest and AiAuditCommandTest's KumlHome sandboxing.
        lateinit var tempPluginsDir: java.nio.file.Path

        beforeSpec {
            tempPluginsDir = Files.createTempDirectory("kuml-plugin-command-test-")
            tempPluginsDir.toFile().deleteOnExit()
            PluginScanPath.overrideUserPluginDirForTest(tempPluginsDir)
        }

        afterSpec {
            PluginScanPath.clearTestOverride()
        }

        beforeEach { PluginRegistry.clearForTest() }
        afterEach { PluginRegistry.clearForTest() }

        // ── helpers ───────────────────────────────────────────────────────────────

        fun fakeManifest(
            id: String = "dev.kuml.plugin.test",
            name: String = "Test Plugin",
            version: String = "1.0.0",
            permissions: List<String> = emptyList(),
            category: String = "theme",
        ) = PluginManifest(
            schemaVersion = 1,
            id = id,
            name = name,
            version = version,
            kumlVersionRange = ">=0.1.0",
            extensions = listOf(ExtensionEntry(category, "test.Impl", id)),
            permissions = permissions,
        )

        fun registerFake(
            id: String = "dev.kuml.plugin.test",
            name: String = "Test Plugin",
            version: String = "1.0.0",
            permissions: List<String> = emptyList(),
            category: String = "theme",
        ) = PluginRegistry.register(
            LoadedPlugin(fakeManifest(id, name, version, permissions, category), emptyList(), null),
        )

        // ── kuml plugin list ──────────────────────────────────────────────────────

        "list: empty registry prints no-plugins message" {
            val result = PluginListCommand().test("")
            result.statusCode shouldBe 0
            result.output shouldContain "No plugins installed"
        }

        "list: shows installed plugin id and version" {
            registerFake(id = "dev.kuml.plugin.list-test", name = "List Test", version = "2.0.0")
            val result = PluginListCommand().test("")
            result.output shouldContain "dev.kuml.plugin.list-test"
            result.output shouldContain "2.0.0"
            result.output shouldContain "List Test"
        }

        "list: shows plugin count in header" {
            registerFake(id = "plugin.a", name = "A", category = "theme")
            registerFake(id = "plugin.b", name = "B", category = "codegen")
            val result = PluginListCommand().test("")
            result.output shouldContain "Installed plugins (2)"
        }

        "list: shows categories correctly" {
            registerFake(id = "cat-test", name = "Cat", category = "codegen")
            val result = PluginListCommand().test("")
            result.output shouldContain "codegen"
        }

        // ── kuml plugin info ──────────────────────────────────────────────────────

        "info: unknown id exits with PLUGIN_NOT_FOUND (40)" {
            val result = PluginInfoCommand().test("unknown.plugin.id")
            result.statusCode shouldBe 40
        }

        "info: known id shows id and version" {
            registerFake(id = "dev.kuml.plugin.info-test", name = "Info Test", version = "3.0.0")
            val result = PluginInfoCommand().test("dev.kuml.plugin.info-test")
            result.statusCode shouldBe 0
            result.output shouldContain "Info Test"
            result.output shouldContain "3.0.0"
            result.output shouldContain "dev.kuml.plugin.info-test"
        }

        "info: shows permissions when declared" {
            registerFake(id = "perm-test", permissions = listOf("fs.read", "fs.write"))
            val result = PluginInfoCommand().test("perm-test")
            result.output shouldContain "fs.read"
            result.output shouldContain "fs.write"
        }

        "info: shows 'none' for plugins without permissions" {
            registerFake(id = "no-perm-test")
            val result = PluginInfoCommand().test("no-perm-test")
            result.output shouldContain "Permissions:  none"
        }

        "info: shows maintainer when set" {
            PluginRegistry.register(
                LoadedPlugin(
                    PluginManifest(
                        schemaVersion = 1,
                        id = "maintainer-test",
                        name = "Maintainer Test",
                        version = "1.0.0",
                        kumlVersionRange = ">=0.1.0",
                        extensions = listOf(ExtensionEntry("theme", "test.Impl", "maintainer-test")),
                        maintainer = "Test Author <test@example.com>",
                    ),
                    emptyList(),
                    null,
                ),
            )
            val result = PluginInfoCommand().test("maintainer-test")
            result.output shouldContain "Test Author"
        }

        "info: shows signature status as unsigned" {
            registerFake(id = "sig-test")
            val result = PluginInfoCommand().test("sig-test")
            result.output shouldContain "unsigned"
        }

        // ── kuml plugin permissions ───────────────────────────────────────────────

        "permissions: unknown id exits with PLUGIN_NOT_FOUND (40)" {
            val result = PluginPermissionsCommand().test("no.such.plugin")
            result.statusCode shouldBe 40
        }

        "permissions: plugin with no permissions prints none message" {
            registerFake(id = "no-perms", permissions = emptyList())
            val result = PluginPermissionsCommand().test("no-perms")
            result.statusCode shouldBe 0
            result.output shouldContain "No permissions declared"
        }

        "permissions: shows process.exec as HIGH RISK" {
            registerFake(id = "risky", permissions = listOf("process.exec"), category = "reverse")
            val result = PluginPermissionsCommand().test("risky")
            result.output shouldContain "HIGH RISK"
        }

        "permissions: shows network.http as NETWORK" {
            registerFake(id = "net-plugin", permissions = listOf("network.http"), category = "codegen")
            val result = PluginPermissionsCommand().test("net-plugin")
            result.output shouldContain "NETWORK"
        }

        "permissions: shows fs.read description" {
            registerFake(id = "fs-plugin", permissions = listOf("fs.read"), category = "codegen")
            val result = PluginPermissionsCommand().test("fs-plugin")
            result.output shouldContain "fs.read"
            result.output shouldContain "read source files"
        }

        // ── kuml plugin remove ────────────────────────────────────────────────────

        "remove: unknown id exits with PLUGIN_NOT_FOUND (40)" {
            val result = PluginRemoveCommand().test("not.installed")
            result.statusCode shouldBe 40
        }

        "remove: known id removes from registry" {
            registerFake(id = "removable")
            val result = PluginRemoveCommand().test("removable")
            result.statusCode shouldBe 0
            PluginRegistry.get("removable") shouldBe null
        }

        "remove: output confirms removal" {
            registerFake(id = "to-remove")
            val result = PluginRemoveCommand().test("to-remove")
            result.output shouldContain "Removed"
            result.output shouldContain "to-remove"
        }

        // ── kuml plugin install ───────────────────────────────────────────────────

        "install: jar without manifest exits with PLUGIN_NOT_FOUND (40)" {
            val jar = createJarWithoutManifest()
            try {
                val result = PluginInstallCommand().test(listOf(jar.absolutePath))
                result.statusCode shouldBe 40
            } finally {
                jar.delete()
            }
        }

        "install: jar with invalid JSON manifest exits with PLUGIN_NOT_FOUND (40)" {
            val jar = createJarWithManifest("{invalid json}")
            try {
                val result = PluginInstallCommand().test(listOf(jar.absolutePath))
                result.statusCode shouldBe 40
            } finally {
                jar.delete()
            }
        }

        // ── kuml plugin search ───────────────────────────────────────────────────

        "search: lists all plugins when no query given" {
            val (server, port) = startMockRegistry(MOCK_INDEX_JSON)
            try {
                val cmd = PluginSearchCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("")
                result.statusCode shouldBe 0
                result.output shouldContain "Available plugins (2)"
                result.output shouldContain "dev.kuml.plugin.pdv-theme"
                result.output shouldContain "dev.kuml.plugin.elk-layout"
            } finally {
                server.stop(0)
            }
        }

        "search: filters by query substring (case-insensitive)" {
            val (server, port) = startMockRegistry(MOCK_INDEX_JSON)
            try {
                val cmd = PluginSearchCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("elk")
                result.statusCode shouldBe 0
                result.output shouldContain "Matching plugins (1)"
                result.output shouldContain "dev.kuml.plugin.elk-layout"
                result.output shouldNotContain "pdv-theme"
            } finally {
                server.stop(0)
            }
        }

        "search: --category filter shows only matching category" {
            val (server, port) = startMockRegistry(MOCK_INDEX_JSON)
            try {
                val cmd = PluginSearchCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("--category theme")
                result.statusCode shouldBe 0
                result.output shouldContain "Matching plugins (1)"
                result.output shouldContain "dev.kuml.plugin.pdv-theme"
                result.output shouldNotContain "elk-layout"
            } finally {
                server.stop(0)
            }
        }

        "search: no results prints hint to broaden query" {
            val (server, port) = startMockRegistry(MOCK_INDEX_JSON)
            try {
                val cmd = PluginSearchCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("zzz-no-match")
                result.statusCode shouldBe 0
                result.output shouldContain "No plugins found"
                result.output shouldContain "without filters"
            } finally {
                server.stop(0)
            }
        }

        "search: shows kumlVersionRange when present" {
            val (server, port) = startMockRegistry(MOCK_INDEX_JSON)
            try {
                val cmd = PluginSearchCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("pdv")
                result.statusCode shouldBe 0
                result.output shouldContain ">=0.13.0"
            } finally {
                server.stop(0)
            }
        }

        "search: registry unreachable exits with ONLINE_ERROR (1)" {
            // Port 1 is privileged and never open → connection refused immediately
            val cmd = PluginSearchCommand(PluginRegistryClient("http://127.0.0.1:1", timeoutSeconds = 2))
            val result = cmd.test("")
            result.statusCode shouldBe 1
        }

        // ── kuml plugin info: registry stats (V3.1.12) ───────────────────────

        "info: shows rating, download count and reviews from registry" {
            val indexWithStats =
                """
                {
                  "schemaVersion": 1,
                  "plugins": [
                    {
                      "id": "dev.kuml.plugin.info-stats",
                      "category": "theme",
                      "name": "Info Stats Plugin",
                      "version": "1.0.0",
                      "manifest": "plugins/info-stats/kuml-plugin.json",
                      "downloads": "plugins/info-stats/releases/",
                      "downloadCount": 1847,
                      "rating": 4.3,
                      "ratingCount": 12,
                      "reviews": [
                        { "author": "alice", "rating": 4, "comment": "Works great.", "date": "2026-06-20" },
                        { "author": "bob",   "rating": 5, "comment": "Fast.",        "date": "2026-06-18" },
                        { "author": "carol", "rating": 4, "comment": "Nice one.",    "date": "2026-06-15" },
                        { "author": "dave",  "rating": 4, "comment": "Solid.",       "date": "2026-06-10" }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            val (server, port) = startMockRegistry(indexWithStats)
            try {
                registerFake(id = "dev.kuml.plugin.info-stats", name = "Info Stats Plugin", version = "1.0.0")
                val cmd = PluginInfoCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("dev.kuml.plugin.info-stats")
                result.statusCode shouldBe 0
                result.output shouldContain "4.3/5.0"
                result.output shouldContain "1,847"
                result.output shouldContain "alice"
                result.output shouldContain "... 1 more"
            } finally {
                server.stop(0)
            }
        }

        "info: registry unreachable still prints local info (graceful degradation)" {
            // No mock server — client will fail immediately
            registerFake(id = "dev.kuml.plugin.offline-test", name = "Offline Test", version = "2.0.0")
            val cmd = PluginInfoCommand(PluginRegistryClient("http://127.0.0.1:1", timeoutSeconds = 2))
            val result = cmd.test("dev.kuml.plugin.offline-test")
            result.statusCode shouldBe 0
            result.output shouldContain "Offline Test"
            result.output shouldContain "2.0.0"
            result.output shouldContain "dev.kuml.plugin.offline-test"
        }

        "info: plugin not in registry shows local info without stats block" {
            // Registry returns an empty index — plugin is not listed
            val emptyIndex =
                """{"schemaVersion":1,"plugins":[]}"""
            val (server, port) = startMockRegistry(emptyIndex)
            try {
                registerFake(id = "dev.kuml.plugin.not-listed", name = "Not Listed", version = "1.0.0")
                val cmd = PluginInfoCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("dev.kuml.plugin.not-listed")
                result.statusCode shouldBe 0
                result.output shouldContain "Not Listed"
                result.output shouldNotContain "Rating:"
                result.output shouldNotContain "Downloads:"
            } finally {
                server.stop(0)
            }
        }

        // ── kuml plugin search: --sort + stats (V3.1.12) ─────────────────────

        "search --sort=downloads orders by download count descending" {
            val (server, port) = startMockRegistry(MOCK_INDEX_WITH_STATS_JSON)
            try {
                val cmd = PluginSearchCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("--sort downloads")
                result.statusCode shouldBe 0
                // elk-layout has 5000 downloads vs pdv-theme 1847 → elk appears first
                val elkPos = result.output.indexOf("elk-layout")
                val pdvPos = result.output.indexOf("pdv-theme")
                (elkPos < pdvPos) shouldBe true
            } finally {
                server.stop(0)
            }
        }

        "search --sort=rating orders highest rating first" {
            val (server, port) = startMockRegistry(MOCK_INDEX_WITH_STATS_JSON)
            try {
                val cmd = PluginSearchCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("--sort rating")
                result.statusCode shouldBe 0
                // pdv-theme rating=4.8 > elk-layout rating=4.2 → pdv appears first
                val pdvPos = result.output.indexOf("pdv-theme")
                val elkPos = result.output.indexOf("elk-layout")
                (pdvPos < elkPos) shouldBe true
            } finally {
                server.stop(0)
            }
        }

        "search --sort=name (default) orders alphabetically by name" {
            val (server, port) = startMockRegistry(MOCK_INDEX_WITH_STATS_JSON)
            try {
                val cmd = PluginSearchCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("--sort name")
                result.statusCode shouldBe 0
                // "ELK Layout Engine" < "PdV Branding Theme" alphabetically → elk first
                val elkPos = result.output.indexOf("elk-layout")
                val pdvPos = result.output.indexOf("pdv-theme")
                (elkPos < pdvPos) shouldBe true
            } finally {
                server.stop(0)
            }
        }

        "search shows stars and download count in output" {
            val (server, port) = startMockRegistry(MOCK_INDEX_WITH_STATS_JSON)
            try {
                val cmd = PluginSearchCommand(PluginRegistryClient("http://localhost:$port"))
                val result = cmd.test("")
                result.statusCode shouldBe 0
                // Should show star glyphs and the download arrow
                result.output shouldContain "▼"
                result.output shouldContain "★"
            } finally {
                server.stop(0)
            }
        }

        // ── kuml plugin reload ────────────────────────────────────────────────────

        "reload: runs without exception even with no plugins on disk" {
            val result = PluginReloadCommand().test("")
            result.statusCode shouldBe 0
            result.output shouldContain "reloaded"
        }

        "reload: reports plugin count after reload" {
            val result = PluginReloadCommand().test("")
            // Output must contain "plugin(s) loaded"
            result.output shouldContain "plugin(s) loaded"
        }
    })

// ── Test helpers ──────────────────────────────────────────────────────────────

/**
 * Minimal registry index served by [startMockRegistry] for `PluginSearchCommand` tests.
 * Contains two entries so both "all" and "filtered" paths can be exercised.
 */
private val MOCK_INDEX_JSON =
    """
    {
      "schemaVersion": 1,
      "baseUrl": "http://localhost",
      "plugins": [
        {
          "id": "dev.kuml.plugin.pdv-theme",
          "category": "theme",
          "name": "PdV Branding Theme",
          "version": "1.0.0",
          "kumlVersionRange": ">=0.13.0",
          "manifest": "plugins/dev.kuml.plugin.pdv-theme/kuml-plugin.json",
          "downloads": "plugins/dev.kuml.plugin.pdv-theme/releases/",
          "maintainer": "Partei der Vernunft"
        },
        {
          "id": "dev.kuml.plugin.elk-layout",
          "category": "layout",
          "name": "ELK Layout Engine",
          "version": "2.0.0",
          "manifest": "plugins/dev.kuml.plugin.elk-layout/kuml-plugin.json",
          "downloads": "plugins/dev.kuml.plugin.elk-layout/releases/"
        }
      ]
    }
    """.trimIndent()

/**
 * Extended mock registry JSON with V3.1.12 stats fields for testing ratings/downloads/sort.
 * pdv-theme: rating=4.8 (higher), downloadCount=1847 (lower)
 * elk-layout: rating=4.2 (lower), downloadCount=5000 (higher)
 */
private val MOCK_INDEX_WITH_STATS_JSON =
    """
    {
      "schemaVersion": 1,
      "baseUrl": "http://localhost",
      "plugins": [
        {
          "id": "dev.kuml.plugin.pdv-theme",
          "category": "theme",
          "name": "PdV Branding Theme",
          "version": "1.0.0",
          "kumlVersionRange": ">=0.13.0",
          "manifest": "plugins/dev.kuml.plugin.pdv-theme/kuml-plugin.json",
          "downloads": "plugins/dev.kuml.plugin.pdv-theme/releases/",
          "downloadCount": 1847,
          "rating": 4.8,
          "ratingCount": 20,
          "reviews": [],
          "maintainer": "Partei der Vernunft"
        },
        {
          "id": "dev.kuml.plugin.elk-layout",
          "category": "layout",
          "name": "ELK Layout Engine",
          "version": "2.0.0",
          "manifest": "plugins/dev.kuml.plugin.elk-layout/kuml-plugin.json",
          "downloads": "plugins/dev.kuml.plugin.elk-layout/releases/",
          "downloadCount": 5000,
          "rating": 4.2,
          "ratingCount": 10,
          "reviews": []
        }
      ]
    }
    """.trimIndent()

/**
 * Starts a lightweight in-process HTTP server on a random free port that serves
 * [indexJson] at `/plugins/index.json` (the path [PluginRegistryClient.fetchIndex] requests).
 *
 * @return Pair of the started [HttpServer] and the actual port number.
 *         Caller must call `server.stop(0)` in a `finally` block.
 */
private fun startMockRegistry(indexJson: String): Pair<HttpServer, Int> {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/plugins/index.json") { exchange ->
        val body = indexJson.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.start()
    return server to server.address.port
}

private fun createJarWithoutManifest(): java.io.File {
    val jar = java.io.File.createTempFile("test-plugin-", ".jar")
    ZipOutputStream(jar.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("META-INF/"))
        zip.closeEntry()
    }
    return jar
}

private fun createJarWithManifest(content: String): java.io.File {
    val jar = java.io.File.createTempFile("test-plugin-manifest-", ".jar")
    ZipOutputStream(jar.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("kuml-plugin.json"))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }
    return jar
}
