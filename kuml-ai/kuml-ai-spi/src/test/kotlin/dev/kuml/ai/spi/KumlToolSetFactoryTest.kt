package dev.kuml.ai.spi

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Stub ToolSet-like return value — no Koog on the classpath for spi tests. */
private class StubToolSetResult

private class StubToolSetFactory : KumlToolSetFactory {
    override val id: String = "jira-tools"
    override val displayName: String = "Jira Tools"
    override val requiredCapabilities: Set<ToolSetCapability> =
        setOf(ToolSetCapability.NETWORK)

    override fun create(context: Any): Any = StubToolSetResult()
}

private class NetworkAndFileFactory : KumlToolSetFactory {
    override val id: String = "network-file-tools"
    override val displayName: String = "Network & File Tools"
    override val requiredCapabilities: Set<ToolSetCapability> =
        setOf(ToolSetCapability.NETWORK, ToolSetCapability.FILE_SYSTEM, ToolSetCapability.SHELL)

    override fun create(context: Any): Any = StubToolSetResult()
}

class KumlToolSetFactoryTest :
    FunSpec({

        test("stub factory returns expected id and displayName") {
            val factory: KumlToolSetFactory = StubToolSetFactory()
            factory.id shouldBe "jira-tools"
            factory.displayName shouldBe "Jira Tools"
        }

        test("stub factory requiredCapabilities contains NETWORK") {
            val factory = StubToolSetFactory()
            factory.requiredCapabilities shouldBe setOf(ToolSetCapability.NETWORK)
        }

        test("create() returns a non-null Any without Koog types on the classpath") {
            val factory = StubToolSetFactory()
            val result = factory.create("fake-context")
            result shouldNotBe null
            (result is StubToolSetResult) shouldBe true
        }

        test("ToolSetCapability enum has exactly FILE_SYSTEM, NETWORK, SHELL") {
            ToolSetCapability.entries shouldHaveSize 3
            ToolSetCapability.entries.map { it.name }.toSet() shouldBe
                setOf("FILE_SYSTEM", "NETWORK", "SHELL")
        }

        test("factory with multiple capabilities reports all of them") {
            val factory = NetworkAndFileFactory()
            factory.requiredCapabilities shouldBe
                setOf(
                    ToolSetCapability.NETWORK,
                    ToolSetCapability.FILE_SYSTEM,
                    ToolSetCapability.SHELL,
                )
        }
    })
