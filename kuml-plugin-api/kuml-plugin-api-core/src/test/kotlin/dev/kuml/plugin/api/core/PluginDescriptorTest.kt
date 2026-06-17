package dev.kuml.plugin.api.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PluginDescriptorTest :
    FunSpec({

        test("PluginVersion.parse parses standard semver string") {
            val v = PluginVersion.parse("1.2.3")
            v shouldBe PluginVersion(1, 2, 3)
        }

        test("PluginVersion.toString produces major.minor.patch") {
            PluginVersion(1, 2, 3).toString() shouldBe "1.2.3"
        }

        test("PluginVersion comparison is correct: 1.0.0 < 1.1.0 < 2.0.0") {
            val v100 = PluginVersion(1, 0, 0)
            val v110 = PluginVersion(1, 1, 0)
            val v200 = PluginVersion(2, 0, 0)

            (v100 < v110) shouldBe true
            (v110 < v200) shouldBe true
            (v100 < v200) shouldBe true
            (v200 > v110) shouldBe true
        }

        test("PluginDescriptor with mandatory fields only uses correct defaults") {
            val descriptor =
                PluginDescriptor(
                    id = "my-plugin",
                    name = "My Plugin",
                    version = PluginVersion(1, 0, 0),
                    kumlVersionRange = KumlVersionRange(">=3.0.27"),
                    capabilities = setOf(PluginCapability.THEME),
                )

            descriptor.requiredPermissions shouldBe emptySet()
            descriptor.maintainer shouldBe ""
            descriptor.homepage shouldBe ""
            descriptor.licenseSpdx shouldBe "Apache-2.0"
        }

        test("PluginDescriptor with all optional fields set") {
            val descriptor =
                PluginDescriptor(
                    id = "acme-codegen",
                    name = "ACME Code Generator",
                    version = PluginVersion(2, 1, 0),
                    kumlVersionRange = KumlVersionRange(">=3.0.27, <4.0.0"),
                    capabilities = setOf(PluginCapability.CODEGEN),
                    requiredPermissions = setOf(PluginPermission.FS_READ, PluginPermission.FS_WRITE),
                    maintainer = "ACME Corp",
                    homepage = "https://acme.example/kuml-plugin",
                    licenseSpdx = "MIT",
                )

            descriptor.id shouldBe "acme-codegen"
            descriptor.requiredPermissions shouldBe setOf(PluginPermission.FS_READ, PluginPermission.FS_WRITE)
            descriptor.licenseSpdx shouldBe "MIT"
        }

        test("PluginDescriptor round-trips through kotlinx.serialization JSON") {
            val original =
                PluginDescriptor(
                    id = "test-theme-plugin",
                    name = "Test Theme",
                    version = PluginVersion(1, 0, 0),
                    kumlVersionRange = KumlVersionRange(">=3.0.27"),
                    capabilities = setOf(PluginCapability.THEME),
                    maintainer = "Test Author",
                )

            val json = Json.encodeToString(original)
            val decoded = Json.decodeFromString<PluginDescriptor>(json)

            decoded shouldBe original
            json shouldNotBe ""
        }
    })
