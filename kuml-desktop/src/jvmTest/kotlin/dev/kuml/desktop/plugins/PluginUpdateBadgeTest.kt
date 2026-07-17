package dev.kuml.desktop.plugins

import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.loader.registry.PluginRegistryEntry
import dev.kuml.plugin.loader.registry.PluginUpdateInfo
import dev.kuml.plugin.loader.registry.UpdateCheckResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * V3.1.11 — Unit tests for [computeUpdateBadge].
 *
 * Pure function — no Compose test harness needed.
 */
class PluginUpdateBadgeTest :
    FunSpec({

        fun makeResult(
            updateCount: Int,
            reachable: Boolean,
        ): UpdateCheckResult {
            // Build enough PluginUpdateInfo entries to produce the desired updateCount
            val plugins =
                (1..maxOf(updateCount, 1))
                    .map { i ->
                        val installed = PluginVersion(1, 0, 0)
                        val latest = if (i <= updateCount) PluginVersion(2, 0, 0) else PluginVersion(1, 0, 0)
                        val entry =
                            if (reachable) {
                                PluginRegistryEntry(
                                    id = "p$i",
                                    category = "theme",
                                    name = "P$i",
                                    version = latest.toString(),
                                    manifest = "plugins/p$i/kuml-plugin.json",
                                    downloads = "https://example.com/p$i.jar",
                                )
                            } else {
                                null
                            }
                        PluginUpdateInfo(
                            id = "p$i",
                            installed = installed,
                            latest = if (reachable) latest else null,
                            registryEntry = entry,
                        )
                    }.let { infos ->
                        // If updateCount == 0 and reachable, we need a list but with no updates
                        if (updateCount == 0 && reachable) {
                            listOf(
                                PluginUpdateInfo(
                                    id = "p1",
                                    installed = PluginVersion(1, 0, 0),
                                    latest = PluginVersion(1, 0, 0),
                                    registryEntry =
                                        PluginRegistryEntry(
                                            id = "p1",
                                            category = "theme",
                                            name = "P1",
                                            version = "1.0.0",
                                            manifest = "plugins/p1/kuml-plugin.json",
                                            downloads = "https://example.com/p1.jar",
                                        ),
                                ),
                            )
                        } else {
                            infos
                        }
                    }
            return UpdateCheckResult(
                plugins = plugins,
                registryReachable = reachable,
            )
        }

        test("3 updates returns '3 updates' (plural)") {
            val result = makeResult(updateCount = 3, reachable = true)
            computeUpdateBadge(result) shouldBe "3 updates"
        }

        test("1 update returns '1 update' (singular)") {
            val result = makeResult(updateCount = 1, reachable = true)
            computeUpdateBadge(result) shouldBe "1 update"
        }

        test("0 updates returns null — badge hidden") {
            val result = makeResult(updateCount = 0, reachable = true)
            computeUpdateBadge(result) shouldBe null
        }

        test("registry unreachable returns null regardless of installed count") {
            val result = makeResult(updateCount = 5, reachable = false)
            computeUpdateBadge(result) shouldBe null
        }

        test("empty plugins list returns null") {
            val result = UpdateCheckResult(plugins = emptyList(), registryReachable = true)
            computeUpdateBadge(result) shouldBe null
        }

        test("reachable=false with updateCount=0 returns null") {
            val result = UpdateCheckResult(plugins = emptyList(), registryReachable = false)
            computeUpdateBadge(result) shouldBe null
        }

        test("2 updates returns '2 updates' (plural)") {
            val result = makeResult(updateCount = 2, reachable = true)
            computeUpdateBadge(result) shouldBe "2 updates"
        }
    })
