package dev.kuml.plugin.api.reverse

import dev.kuml.codegen.reverse.KumlReverseEngine
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.plugin.api.core.KumlVersionRange
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginDescriptor
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class KumlReversePluginTest :
    FunSpec({

        fun fakeEngine(engineId: String) =
            object : KumlReverseEngine {
                override val id = engineId
                override val description = "Fake Reverse Engine ($engineId)"

                override suspend fun analyze(request: ReverseRequest): ReverseResult = ReverseResult.Failure(errors = emptyList())
            }

        val testDescriptor =
            PluginDescriptor(
                id = "test-reverse-plugin",
                name = "Test Reverse Plugin",
                version = PluginVersion(1, 0, 0),
                kumlVersionRange = KumlVersionRange(">=3.0.27"),
                capabilities = setOf(PluginCapability.REVERSE),
                requiredPermissions = setOf(PluginPermission.FS_READ),
            )

        val testPlugin =
            object : KumlReversePlugin {
                override val descriptor = testDescriptor

                override fun engines() = listOf(fakeEngine("test-java"))
            }

        test("KumlReversePlugin engines() must not be empty") {
            testPlugin.engines().shouldNotBeEmpty()
        }

        test("descriptor requiredPermissions contains FS_READ") {
            testPlugin.descriptor.requiredPermissions shouldContain PluginPermission.FS_READ
        }

        test("minimal KumlReversePlugin with fake engine is constructable") {
            val engines = testPlugin.engines()
            engines.size shouldBe 1
            engines.first().id shouldBe "test-java"
            engines.first().description shouldBe "Fake Reverse Engine (test-java)"
        }

        test("descriptor capabilities contains REVERSE") {
            testPlugin.descriptor.capabilities shouldContain PluginCapability.REVERSE
        }

        test("engine IDs are unique within a plugin") {
            val plugin =
                object : KumlReversePlugin {
                    override val descriptor = testDescriptor

                    override fun engines() =
                        listOf(
                            fakeEngine("engine-java"),
                            fakeEngine("engine-kotlin"),
                        )
                }

            val ids = plugin.engines().map { it.id }
            ids.toSet().size shouldBe ids.size // all IDs are unique
        }
    })
