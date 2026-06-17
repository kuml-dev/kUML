package dev.kuml.plugin.loader.permission

import dev.kuml.plugin.api.core.KumlPlugin
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.plugin.loader.error.PluginPermissionDeniedException
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PluginPermissionEnforcerTest :
    StringSpec({

        fun plugin(vararg permissions: PluginPermission): KumlPlugin =
            object : KumlPlugin {
                override val descriptor =
                    PluginDescriptor(
                        id = "test.plugin",
                        name = "Test",
                        version = PluginVersion(1, 0, 0),
                        kumlVersionRange = KumlVersionRange(">=0.12.0"),
                        capabilities = setOf(PluginCapability.CODEGEN),
                        requiredPermissions = permissions.toSet(),
                    )
            }

        // ── has() ─────────────────────────────────────────────────────────────────

        "has() returns true when permission is declared" {
            val p = plugin(PluginPermission.FS_READ)
            PluginPermissionEnforcer.has(p, PluginPermission.FS_READ) shouldBe true
        }

        "has() returns false when permission is not declared" {
            val p = plugin(PluginPermission.FS_READ)
            PluginPermissionEnforcer.has(p, PluginPermission.FS_WRITE) shouldBe false
        }

        "has() returns false when no permissions are declared" {
            val p = plugin()
            PluginPermissionEnforcer.has(p, PluginPermission.FS_READ) shouldBe false
        }

        "has() returns true for RENDER_READ_RESOURCES when declared" {
            val p = plugin(PluginPermission.RENDER_READ_RESOURCES)
            PluginPermissionEnforcer.has(p, PluginPermission.RENDER_READ_RESOURCES) shouldBe true
        }

        // ── require() ─────────────────────────────────────────────────────────────

        "require() passes without exception when permission is declared" {
            val p = plugin(PluginPermission.FS_WRITE)
            shouldNotThrow<PluginPermissionDeniedException> {
                PluginPermissionEnforcer.require(p, PluginPermission.FS_WRITE)
            }
        }

        "require() throws PluginPermissionDeniedException when not declared" {
            val p = plugin(PluginPermission.FS_READ)
            val ex =
                shouldThrow<PluginPermissionDeniedException> {
                    PluginPermissionEnforcer.require(p, PluginPermission.FS_WRITE)
                }
            ex.pluginId shouldBe "test.plugin"
            ex.requiredPermission shouldBe PluginPermission.FS_WRITE
        }

        "require() exception message contains plugin id and permission" {
            val p = plugin()
            val ex =
                shouldThrow<PluginPermissionDeniedException> {
                    PluginPermissionEnforcer.require(p, PluginPermission.PROCESS_EXEC)
                }
            ex.message!!.contains("test.plugin") shouldBe true
            ex.message!!.contains("PROCESS_EXEC") shouldBe true
        }

        // ── withPermission() ──────────────────────────────────────────────────────

        "withPermission() executes block when permission is declared" {
            val p = plugin(PluginPermission.NETWORK_HTTP)
            var executed = false
            PluginPermissionEnforcer.withPermission(p, PluginPermission.NETWORK_HTTP) {
                executed = true
            }
            executed shouldBe true
        }

        "withPermission() returns block result when permission is declared" {
            val p = plugin(PluginPermission.FS_READ)
            val result = PluginPermissionEnforcer.withPermission(p, PluginPermission.FS_READ) { 42 }
            result shouldBe 42
        }

        "withPermission() throws when permission is not declared" {
            val p = plugin()
            shouldThrow<PluginPermissionDeniedException> {
                PluginPermissionEnforcer.withPermission(p, PluginPermission.PROCESS_EXEC) { }
            }
        }

        "withPermission() does not execute block when permission is denied" {
            val p = plugin()
            var executed = false
            runCatching {
                PluginPermissionEnforcer.withPermission(p, PluginPermission.FS_WRITE) {
                    executed = true
                }
            }
            executed shouldBe false
        }

        // ── multi-permission scenarios ────────────────────────────────────────────

        "plugin with multiple permissions: all declared permissions pass has()" {
            val p = plugin(PluginPermission.FS_READ, PluginPermission.FS_WRITE)
            PluginPermissionEnforcer.has(p, PluginPermission.FS_READ) shouldBe true
            PluginPermissionEnforcer.has(p, PluginPermission.FS_WRITE) shouldBe true
        }

        "plugin with multiple permissions: undeclared permission still denied" {
            val p = plugin(PluginPermission.FS_READ, PluginPermission.FS_WRITE)
            PluginPermissionEnforcer.has(p, PluginPermission.NETWORK_HTTP) shouldBe false
        }

        "plugin with no permissions is denied all non-trivial permissions" {
            val p = plugin()
            listOf(
                PluginPermission.FS_READ,
                PluginPermission.FS_WRITE,
                PluginPermission.NETWORK_HTTP,
                PluginPermission.PROCESS_EXEC,
            ).forEach { perm ->
                PluginPermissionEnforcer.has(p, perm) shouldBe false
            }
        }
    })
