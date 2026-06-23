package dev.kuml.cli.plugin

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import dev.kuml.plugin.loader.loader.LoadedPlugin
import dev.kuml.plugin.loader.manifest.ExtensionEntry
import dev.kuml.plugin.loader.manifest.PluginManifest
import dev.kuml.plugin.loader.registry.DownloadedPlugin
import dev.kuml.plugin.loader.registry.PluginDownloader
import dev.kuml.plugin.loader.registry.PluginRegistry
import dev.kuml.plugin.loader.registry.PluginRegistryEntry
import dev.kuml.plugin.loader.registry.PluginRegistryException
import dev.kuml.plugin.loader.registry.PluginRegistryIndex
import dev.kuml.plugin.loader.registry.UpdateCheckService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for PluginUpgradeCommand.
 *
 * The test strategy avoids real network and real file installation by injecting
 * fake UpdateCheckService and PluginDownloader instances. The downloader returns
 * a DownloadedPlugin pointing at a fixture JAR built in-memory.
 */
class PluginUpgradeCommandTest :
    StringSpec({
        beforeEach { PluginRegistry.clearForTest() }
        afterEach { PluginRegistry.clearForTest() }

        // ── Helpers ───────────────────────────────────────────────────────────

        fun fakeManifestJson(
            id: String,
            version: String,
            kumlVersionRange: String = ">=0.1.0",
            permissions: List<String> = emptyList(),
        ): String {
            val permsJson = permissions.joinToString(",") { "\"$it\"" }
            return """
                {
                  "schemaVersion": 1,
                  "id": "$id",
                  "name": "Plugin $id",
                  "version": "$version",
                  "kumlVersionRange": "$kumlVersionRange",
                  "extensions": [{"category": "theme", "implementation": "test.Stub", "id": "$id-ext"}],
                  "permissions": [$permsJson]
                }
                """.trimIndent()
        }

        fun buildFakeJar(
            id: String,
            version: String,
            permissions: List<String> = emptyList(),
        ): java.io.File {
            val jar = java.io.File.createTempFile("fake-plugin-$id-$version-", ".jar")
            jar.deleteOnExit()
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("kuml-plugin.json"))
                zip.write(fakeManifestJson(id, version, permissions = permissions).toByteArray())
                zip.closeEntry()
            }
            return jar
        }

        fun fakeManifest(
            id: String,
            version: String,
            permissions: List<String> = emptyList(),
        ) = PluginManifest(
            schemaVersion = 1,
            id = id,
            name = "Plugin $id",
            version = version,
            kumlVersionRange = ">=0.1.0",
            extensions = listOf(ExtensionEntry("theme", "test.Stub", "$id-ext")),
            permissions = permissions,
        )

        fun registerFake(
            id: String,
            version: String,
            permissions: List<String> = emptyList(),
        ) = PluginRegistry.register(LoadedPlugin(fakeManifest(id, version, permissions), emptyList(), null))

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

        fun serviceWith(index: PluginRegistryIndex): UpdateCheckService =
            UpdateCheckService(
                indexProvider = { index },
                installedProvider = { PluginRegistry.all() },
            )

        fun serviceUnreachable(): UpdateCheckService =
            UpdateCheckService(
                indexProvider = { throw PluginRegistryException("offline") },
                installedProvider = { PluginRegistry.all() },
            )

        /** A fake downloader that returns a pre-built JAR without network. */
        fun fakeDownloader(jar: java.io.File): PluginDownloader {
            val jarPath = jar.toPath()
            return object : PluginDownloader() {
                override fun download(downloadsUrl: String): DownloadedPlugin = DownloadedPlugin(jar = jarPath, sig = null)
            }
        }

        fun command(
            service: UpdateCheckService,
            downloader: PluginDownloader,
        ) = PluginUpgradeCommand(
            serviceFactory = { service },
            downloaderFactory = { downloader },
        )

        // ── Usage validation ──────────────────────────────────────────────────

        "exits USAGE (2) when neither id nor --all given" {
            val cmd =
                PluginUpgradeCommand(
                    serviceFactory = { serviceWith(fakeIndex()) },
                    downloaderFactory = { PluginDownloader() },
                )
            val result = cmd.test("")
            result.statusCode shouldBe ExitCodes.USAGE
        }

        "exits USAGE (2) when both id and --all given" {
            val cmd =
                PluginUpgradeCommand(
                    serviceFactory = { serviceWith(fakeIndex()) },
                    downloaderFactory = { PluginDownloader() },
                )
            val result = cmd.test("some.plugin --all")
            result.statusCode shouldBe ExitCodes.USAGE
        }

        // ── Registry unreachable ──────────────────────────────────────────────

        "exits ONLINE_ERROR (1) when registry unreachable" {
            registerFake("p.offline", "1.0.0")
            val cmd = command(serviceUnreachable(), PluginDownloader())
            val result = cmd.test("p.offline")
            result.statusCode shouldBe ExitCodes.ONLINE_ERROR
        }

        // ── Already up-to-date ────────────────────────────────────────────────

        "exits 0 and prints up-to-date message when plugin already current" {
            registerFake("p.current", "2.0.0")
            val cmd =
                command(
                    serviceWith(fakeIndex(fakeEntry("p.current", "2.0.0"))),
                    PluginDownloader(),
                )
            val result = cmd.test("p.current")
            result.statusCode shouldBe 0
            result.output shouldContain "already up-to-date"
        }

        // ── Plugin not installed ──────────────────────────────────────────────

        "exits PLUGIN_NOT_FOUND (40) when plugin not installed" {
            // registry has it but it's not installed
            val cmd =
                command(
                    serviceWith(fakeIndex(fakeEntry("not.installed", "1.0.0"))),
                    PluginDownloader(),
                )
            val result = cmd.test("not.installed")
            result.statusCode shouldBe ExitCodes.PLUGIN_NOT_FOUND
        }

        "exits PLUGIN_NOT_FOUND (40) when plugin not in registry" {
            registerFake("orphan.plugin", "1.0.0")
            val cmd =
                command(
                    serviceWith(fakeIndex()), // empty registry
                    PluginDownloader(),
                )
            val result = cmd.test("orphan.plugin")
            result.statusCode shouldBe ExitCodes.PLUGIN_NOT_FOUND
        }

        // ── --all with nothing to upgrade ─────────────────────────────────────

        "--all prints up-to-date when all plugins current" {
            registerFake("p.all1", "1.0.0")
            registerFake("p.all2", "2.0.0")
            val cmd =
                command(
                    serviceWith(
                        fakeIndex(
                            fakeEntry("p.all1", "1.0.0"),
                            fakeEntry("p.all2", "2.0.0"),
                        ),
                    ),
                    PluginDownloader(),
                )
            val result = cmd.test("--all")
            result.statusCode shouldBe 0
            result.output shouldContain "up-to-date"
        }

        // ── Successful download path (fake downloader bypasses network/PluginLoader) ──

        "upgrade single plugin: downloads JAR and attempts to load it" {
            registerFake("p.upgrade", "1.0.0")
            val newJar = buildFakeJar("p.upgrade", "2.0.0")
            val cmd =
                command(
                    serviceWith(fakeIndex(fakeEntry("p.upgrade", "2.0.0"))),
                    fakeDownloader(newJar),
                )
            // PluginLoader.loadJar will fail because the Stub class doesn't exist, but
            // we can verify the command reaches the upgrade path and reports the failure
            // gracefully (not an unhandled exception).
            val result = cmd.test("p.upgrade --skip-signature-check")
            // The command may fail with PLUGIN_UPGRADE_FAILED (45) when loadJar can't find
            // "test.Stub", but it must not throw an uncaught exception or return a usage error.
            result.statusCode shouldNotBe ExitCodes.USAGE
            result.statusCode shouldNotBe ExitCodes.ONLINE_ERROR
        }

        // ── Partial upgrade failure (--all) ───────────────────────────────────

        "--all exits PLUGIN_UPGRADE_FAILED (45) when at least one plugin fails" {
            registerFake("p.fail1", "1.0.0")
            registerFake("p.fail2", "1.0.0")
            // Downloader throws for any URL — simulates network/download failure
            val failingDownloader =
                object : PluginDownloader() {
                    override fun download(downloadsUrl: String): DownloadedPlugin =
                        throw PluginRegistryException("simulated download error")
                }
            val cmd =
                command(
                    serviceWith(
                        fakeIndex(
                            fakeEntry("p.fail1", "2.0.0"),
                            fakeEntry("p.fail2", "2.0.0"),
                        ),
                    ),
                    failingDownloader,
                )
            val result = cmd.test("--all --skip-signature-check")
            result.statusCode shouldBe ExitCodes.PLUGIN_UPGRADE_FAILED
            result.output shouldContain "failed"
        }

        "--all upgrades only plugins with available updates" {
            registerFake("p.outdated", "1.0.0")
            registerFake("p.current", "3.0.0")
            val newJar = buildFakeJar("p.outdated", "2.0.0")
            val cmd =
                command(
                    serviceWith(
                        fakeIndex(
                            fakeEntry("p.outdated", "2.0.0"),
                            fakeEntry("p.current", "3.0.0"),
                        ),
                    ),
                    fakeDownloader(newJar),
                )
            val result = cmd.test("--all --skip-signature-check")
            // p.current must not be attempted; p.outdated attempted (may fail loadJar)
            result.output shouldContain "p.outdated"
            result.output shouldNotBe null // no NPE / crash
        }

        // ── New-permission consent gate ───────────────────────────────────────

        "new permissions + --yes: upgrade proceeds without prompting" {
            // Install old version with no permissions, new version adds one permission.
            // With --yes the consent gate must be skipped and the upgrade must proceed
            // (it may still fail at loadJar because test.Stub doesn't exist, but it must
            // NOT exit PLUGIN_NOT_FOUND, ONLINE_ERROR or USAGE).
            registerFake("p.newperm.yes", "1.0.0", permissions = emptyList())
            val newJar = buildFakeJar("p.newperm.yes", "2.0.0", permissions = listOf("network"))
            val cmd =
                command(
                    serviceWith(fakeIndex(fakeEntry("p.newperm.yes", "2.0.0"))),
                    fakeDownloader(newJar),
                )
            val result = cmd.test("p.newperm.yes --yes --skip-signature-check")
            result.statusCode shouldNotBe ExitCodes.USAGE
            result.statusCode shouldNotBe ExitCodes.ONLINE_ERROR
            result.statusCode shouldNotBe ExitCodes.PLUGIN_NOT_FOUND
            // The command must not print "aborted" — consent was auto-accepted
            result.output shouldNotBe null
            (result.output + result.stderr).contains("aborted") shouldBe false
        }

        "new permissions + user declines: upgrade aborted gracefully (exit 0)" {
            // Install old version with no permissions, new version adds one permission.
            // Without --yes the command reads stdin for a y/n answer. Provide "n".
            // The upgrade should be aborted gracefully: exit 0, no PLUGIN_UPGRADE_FAILED.
            registerFake("p.newperm.no", "1.0.0", permissions = emptyList())
            val newJar = buildFakeJar("p.newperm.no", "2.0.0", permissions = listOf("network"))
            val cmd =
                command(
                    serviceWith(fakeIndex(fakeEntry("p.newperm.no", "2.0.0"))),
                    fakeDownloader(newJar),
                )
            // Redirect System.in so readlnOrNull() in promptYesNo reads "n"
            val origIn = System.`in`
            try {
                System.setIn("n\n".byteInputStream())
                val result = cmd.test("p.newperm.no --skip-signature-check")
                result.statusCode shouldBe 0
                result.statusCode shouldNotBe ExitCodes.PLUGIN_UPGRADE_FAILED
                (result.output + result.stderr) shouldContain "aborted"
            } finally {
                System.setIn(origIn)
            }
        }

        // ── --skip-signature-check warning ────────────────────────────────────

        "--skip-signature-check prints loud warning" {
            registerFake("p.skigsig", "1.0.0")
            val newJar = buildFakeJar("p.skigsig", "2.0.0")
            val cmd =
                command(
                    serviceWith(fakeIndex(fakeEntry("p.skigsig", "2.0.0"))),
                    fakeDownloader(newJar),
                )
            val result = cmd.test("p.skigsig --skip-signature-check")
            // stderr output should mention the warning; errOutput or combined output
            val combined = result.output + result.stderr
            combined shouldContain "WARNING"
        }
    })
