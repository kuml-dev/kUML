package dev.kuml.ai.tools.registry

import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.kuml.ai.spi.KumlToolSetFactory
import dev.kuml.ai.spi.ToolSetCapability
import dev.kuml.ai.tools.context.AgentEditingContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

// ── Stub ToolSet used in external factory tests ───────────────────────────────

private class StubToolSet : ToolSet {
    @Tool
    fun stubPing(): String = "pong"
}

// ── Stub factory implementations ─────────────────────────────────────────────

private class JiraToolSetFactory : KumlToolSetFactory {
    override val id: String = "jira"
    override val displayName: String = "Jira Tools"
    override val requiredCapabilities: Set<ToolSetCapability> = setOf(ToolSetCapability.NETWORK)

    override fun create(context: Any): Any = StubToolSet()
}

private class AnotherJiraFactory : KumlToolSetFactory {
    override val id: String = "jira"
    override val displayName: String = "Another Jira Tools (duplicate)"
    override val requiredCapabilities: Set<ToolSetCapability> = emptySet()

    override fun create(context: Any): Any = StubToolSet()
}

/** Collision with built-in id. */
private class UmlCollisionFactory : KumlToolSetFactory {
    override val id: String = "uml"
    override val displayName: String = "Bad UML Override"
    override val requiredCapabilities: Set<ToolSetCapability> = emptySet()

    override fun create(context: Any): Any = StubToolSet()
}

/** create() always throws. */
private class ThrowingFactory : KumlToolSetFactory {
    override val id: String = "failing-tools"
    override val displayName: String = "Always Fails"
    override val requiredCapabilities: Set<ToolSetCapability> = emptySet()

    override fun create(context: Any): Any = error("intentional failure in ThrowingFactory")
}

/** create() returns a non-ToolSet value. */
private class WrongTypeFactory : KumlToolSetFactory {
    override val id: String = "wrong-type-tools"
    override val displayName: String = "Wrong Type"
    override val requiredCapabilities: Set<ToolSetCapability> = emptySet()

    override fun create(context: Any): Any = "this is a String, not a ToolSet"
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class KumlToolRegistryExternalDiscoveryTest :
    FunSpec({

        afterSpec {
            // Clear the ServiceLoader cache so this test doesn't affect others.
            KumlToolRegistry.resetExternalCacheForTest()
        }

        test("discoverExternalFrom keeps a unique external factory") {
            val result = KumlToolRegistry.discoverExternalFrom(listOf(JiraToolSetFactory()))
            result shouldHaveSize 1
            result[0].id shouldBe "jira"
        }

        test("discoverExternalFrom filters factory with built-in id collision") {
            val result = KumlToolRegistry.discoverExternalFrom(listOf(UmlCollisionFactory()))
            result shouldHaveSize 0
        }

        test("discoverExternalFrom keeps built-in collision out even when mixed with valid factories") {
            val result =
                KumlToolRegistry.discoverExternalFrom(
                    listOf(UmlCollisionFactory(), JiraToolSetFactory()),
                )
            result shouldHaveSize 1
            result[0].id shouldBe "jira"
        }

        test("discoverExternalFrom: external-vs-external duplicate id — first wins") {
            val first = JiraToolSetFactory()
            val second = AnotherJiraFactory()
            val result = KumlToolRegistry.discoverExternalFrom(listOf(first, second))
            result shouldHaveSize 1
            result[0] shouldBe first
        }

        test("discoverExternalFrom keeps empty list when all factories are dropped") {
            val result =
                KumlToolRegistry.discoverExternalFrom(
                    listOf(UmlCollisionFactory(), AnotherJiraFactory(), JiraToolSetFactory(), JiraToolSetFactory()),
                )
            // UmlCollision: built-in id drop; first Jira: ok; second Jira (AnotherJira): duplicate drop;
            // third Jira (same as first id): duplicate drop
            result shouldHaveSize 1
        }

        test("buildWithExternalFrom merges built-in and external tool sets") {
            val ctx = AgentEditingContext.emptyUml()
            val registry = KumlToolRegistry.buildWithExternalFrom(ctx, listOf(JiraToolSetFactory()))
            val toolNames = registry.tools.map { it.name }
            // Built-in inspection tool must be present
            toolNames.any { it.contains("list_elements") || it.contains("listElements") } shouldBe true
            // External stub tool must be present
            toolNames.any { it.contains("stub") || it.contains("Ping") || it.contains("ping") } shouldBe true
        }

        test("buildWithExternalFrom with empty external list returns built-in registry") {
            val ctx = AgentEditingContext.emptyUml()
            val registry = KumlToolRegistry.buildWithExternalFrom(ctx, emptyList())
            registry shouldNotBe null
            // Built-in tools still present
            val toolNames = registry.tools.map { it.name }
            toolNames.any { it.contains("list_elements") || it.contains("listElements") } shouldBe true
        }

        test("buildWithExternalFrom: failing factory is skipped, others survive") {
            val ctx = AgentEditingContext.emptyUml()
            val registry =
                KumlToolRegistry.buildWithExternalFrom(
                    ctx,
                    listOf(ThrowingFactory(), JiraToolSetFactory()),
                )
            val toolNames = registry.tools.map { it.name }
            // Good external tool survives
            toolNames.any { it.contains("stub") || it.contains("Ping") || it.contains("ping") } shouldBe true
        }

        test("buildWithExternalFrom: factory returning non-ToolSet is skipped, others survive") {
            val ctx = AgentEditingContext.emptyUml()
            val registry =
                KumlToolRegistry.buildWithExternalFrom(
                    ctx,
                    listOf(WrongTypeFactory(), JiraToolSetFactory()),
                )
            val toolNames = registry.tools.map { it.name }
            // Good external tool survives
            toolNames.any { it.contains("stub") || it.contains("Ping") || it.contains("ping") } shouldBe true
        }
    })
