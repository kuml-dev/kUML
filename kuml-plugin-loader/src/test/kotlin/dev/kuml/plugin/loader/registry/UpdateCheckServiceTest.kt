package dev.kuml.plugin.loader.registry

import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.loader.loader.LoadedPlugin
import dev.kuml.plugin.loader.manifest.ExtensionEntry
import dev.kuml.plugin.loader.manifest.PluginManifest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank

class UpdateCheckServiceTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────

        fun fakeManifest(
            id: String,
            version: String,
        ) = PluginManifest(
            schemaVersion = 1,
            id = id,
            name = "Test Plugin $id",
            version = version,
            kumlVersionRange = ">=0.1.0",
            extensions = listOf(ExtensionEntry("theme", "test.Impl", id)),
        )

        fun fakeLoaded(
            id: String,
            version: String,
        ) = LoadedPlugin(fakeManifest(id, version), emptyList(), null)

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

        fun serviceWith(
            installed: List<LoadedPlugin>,
            indexFn: () -> PluginRegistryIndex,
        ) = UpdateCheckService(
            indexProvider = indexFn,
            installedProvider = { installed },
        )

        // ── Test cases ─────────────────────────────────────────────────────────

        test("update available when registry version is newer") {
            val svc =
                serviceWith(
                    installed = listOf(fakeLoaded("plugin.a", "1.0.0")),
                    indexFn = { fakeIndex(fakeEntry("plugin.a", "1.2.0")) },
                )
            val result = svc.check()
            result.registryReachable shouldBe true
            result.updateCount shouldBe 1
            result.hasUpdates shouldBe true
            result.plugins.single().status shouldBe PluginUpdateState.UPDATE_AVAILABLE
            result.plugins.single().latest shouldBe PluginVersion(1, 2, 0)
        }

        test("up-to-date when installed and registry versions match") {
            val svc =
                serviceWith(
                    installed = listOf(fakeLoaded("plugin.b", "2.0.0")),
                    indexFn = { fakeIndex(fakeEntry("plugin.b", "2.0.0")) },
                )
            val result = svc.check()
            result.updateCount shouldBe 0
            result.hasUpdates shouldBe false
            result.plugins.single().status shouldBe PluginUpdateState.UP_TO_DATE
        }

        test("up-to-date when installed version is newer than registry (downgrade not offered)") {
            val svc =
                serviceWith(
                    installed = listOf(fakeLoaded("plugin.c", "2.0.0")),
                    indexFn = { fakeIndex(fakeEntry("plugin.c", "1.0.0")) },
                )
            val result = svc.check()
            result.updateCount shouldBe 0
            result.plugins.single().status shouldBe PluginUpdateState.UP_TO_DATE
            result.plugins.single().updateAvailable shouldBe false
        }

        test("not-in-registry when plugin has no registry entry") {
            val svc =
                serviceWith(
                    installed = listOf(fakeLoaded("plugin.orphan", "1.0.0")),
                    indexFn = { fakeIndex() }, // empty registry
                )
            val result = svc.check()
            result.plugins.single().status shouldBe PluginUpdateState.NOT_IN_REGISTRY
            result.plugins.single().latest shouldBe null
            result.updateCount shouldBe 0
        }

        test("registry unreachable returns graceful result without throwing") {
            val svc =
                serviceWith(
                    installed = listOf(fakeLoaded("plugin.x", "1.0.0")),
                    indexFn = { throw PluginRegistryException("Connection refused") },
                )
            val result = svc.check()
            result.registryReachable shouldBe false
            result.hasUpdates shouldBe false
            result.updateCount shouldBe 0
            result.error shouldNotBe null
            result.error!!.shouldNotBeBlank()
            result.plugins.single().latest shouldBe null
            result.plugins.single().registryReachable shouldBe false
            result.plugins.single().status shouldBe PluginUpdateState.REGISTRY_UNREACHABLE
        }

        test("malformed installed version string is parsed as 0.0.0 without crash") {
            val svc =
                serviceWith(
                    installed = listOf(fakeLoaded("plugin.bad", "abc")),
                    indexFn = { fakeIndex(fakeEntry("plugin.bad", "0.0.1")) },
                )
            val result = svc.check()
            // 0.0.1 > 0.0.0 → update available
            result.plugins.single().installed shouldBe PluginVersion(0, 0, 0)
            result.plugins.single().status shouldBe PluginUpdateState.UPDATE_AVAILABLE
        }

        test("empty installed list returns empty result with no updates") {
            val svc =
                serviceWith(
                    installed = emptyList(),
                    indexFn = { fakeIndex(fakeEntry("any.plugin", "1.0.0")) },
                )
            val result = svc.check()
            result.plugins shouldBe emptyList()
            result.hasUpdates shouldBe false
        }

        test("updateCount on result returns correct count — one fetch, no hidden network call") {
            val svc =
                serviceWith(
                    installed =
                        listOf(
                            fakeLoaded("p1", "1.0.0"),
                            fakeLoaded("p2", "2.0.0"),
                            fakeLoaded("p3", "3.0.0"),
                        ),
                    indexFn = {
                        fakeIndex(
                            fakeEntry("p1", "1.1.0"), // update
                            fakeEntry("p2", "2.0.0"), // up-to-date
                            // p3 missing from registry
                        )
                    },
                )
            val result = svc.check()
            result.updateCount shouldBe 1
        }

        test("multiple plugins some with updates some without") {
            val svc =
                serviceWith(
                    installed =
                        listOf(
                            fakeLoaded("a", "1.0.0"),
                            fakeLoaded("b", "2.0.0"),
                        ),
                    indexFn = {
                        fakeIndex(
                            fakeEntry("a", "1.5.0"),
                            fakeEntry("b", "2.0.0"),
                        )
                    },
                )
            val result = svc.check()
            result.updateCount shouldBe 1
            result.plugins.first { it.id == "a" }.status shouldBe PluginUpdateState.UPDATE_AVAILABLE
            result.plugins.first { it.id == "b" }.status shouldBe PluginUpdateState.UP_TO_DATE
        }

        test("registry entry is carried in PluginUpdateInfo for upgrade use") {
            val entry = fakeEntry("plugin.y", "2.0.0")
            val svc =
                serviceWith(
                    installed = listOf(fakeLoaded("plugin.y", "1.0.0")),
                    indexFn = { fakeIndex(entry) },
                )
            val result = svc.check()
            result.plugins.single().registryEntry shouldBe entry
        }
    })
