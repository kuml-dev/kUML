package dev.kuml.ai.tools.registry

import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.kuml.ai.spi.KumlToolSetFactory
import dev.kuml.ai.spi.ToolSetCapability

/**
 * Test-fixture [KumlToolSetFactory] registered via
 * `META-INF/services/dev.kuml.ai.spi.KumlToolSetFactory` in the test classpath.
 *
 * Used exclusively in [KumlToolRegistryServiceLoaderTest] to verify end-to-end
 * ServiceLoader discovery without a real plugin JAR.
 */
internal class TestKumlToolSetFactory : KumlToolSetFactory {
    override val id: String = "test-fixture-tools"
    override val displayName: String = "Test Fixture Tools"
    override val requiredCapabilities: Set<ToolSetCapability> = emptySet()

    override fun create(context: Any): Any = TestFixtureToolSet()
}

internal class TestFixtureToolSet : ToolSet {
    @Tool
    fun testFixturePing(): String = "fixture-pong"
}
