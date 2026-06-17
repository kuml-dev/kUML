package dev.kuml.plugin.api.codegen

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.core.model.KumlDiagram
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class KumlCodegenPluginTest :
    FunSpec({

        fun fakeGenerator(generatorId: String) =
            object : KumlCodeGenerator {
                override val id = generatorId
                override val displayName = "Fake Generator ($generatorId)"

                override fun generate(
                    diagram: KumlDiagram,
                    outputDir: File,
                    options: Map<String, String>,
                ): List<File> = emptyList()
            }

        val testDescriptor =
            PluginDescriptor(
                id = "test-codegen-plugin",
                name = "Test Codegen Plugin",
                version = PluginVersion(1, 0, 0),
                kumlVersionRange = KumlVersionRange(">=3.0.27"),
                capabilities = setOf(PluginCapability.CODEGEN),
                requiredPermissions = setOf(PluginPermission.FS_READ, PluginPermission.FS_WRITE),
            )

        val testPlugin =
            object : KumlCodegenPlugin {
                override val descriptor = testDescriptor

                override fun generators() = listOf(fakeGenerator("test-kotlin"))
            }

        test("KumlCodegenPlugin generators() must not be empty") {
            testPlugin.generators().shouldNotBeEmpty()
        }

        test("descriptor requiredPermissions contains FS_READ and FS_WRITE") {
            testPlugin.descriptor.requiredPermissions shouldContain PluginPermission.FS_READ
            testPlugin.descriptor.requiredPermissions shouldContain PluginPermission.FS_WRITE
        }

        test("minimal KumlCodegenPlugin with fake generator is constructable") {
            val generators = testPlugin.generators()
            generators.size shouldBe 1
            generators.first().id shouldBe "test-kotlin"
            generators.first().displayName shouldBe "Fake Generator (test-kotlin)"
        }

        test("descriptor capabilities contains CODEGEN") {
            testPlugin.descriptor.capabilities shouldContain PluginCapability.CODEGEN
        }

        test("generator IDs are unique within a plugin") {
            val plugin =
                object : KumlCodegenPlugin {
                    override val descriptor = testDescriptor

                    override fun generators() =
                        listOf(
                            fakeGenerator("gen-a"),
                            fakeGenerator("gen-b"),
                        )
                }

            val ids = plugin.generators().map { it.id }
            ids.toSet().size shouldBe ids.size // all IDs are unique
        }
    })
