package dev.kuml.ai.tools.registry

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * End-to-end test for [KumlToolRegistry.discoverExternal] via a real [java.util.ServiceLoader].
 *
 * The test-classpath services file registers [TestKumlToolSetFactory] under
 * `META-INF/services/dev.kuml.ai.spi.KumlToolSetFactory`. This test verifies
 * that [KumlToolRegistry.discoverExternal] picks it up correctly.
 */
class KumlToolRegistryServiceLoaderTest :
    FunSpec({

        afterSpec {
            KumlToolRegistry.resetExternalCacheForTest()
        }

        test("discoverExternal finds test-fixture factory via ServiceLoader") {
            KumlToolRegistry.resetExternalCacheForTest()

            val factories = KumlToolRegistry.discoverExternal()
            val ids = factories.map { it.id }
            ids shouldContain "test-fixture-tools"
        }

        test("discoverExternal is idempotent — second call returns cached result") {
            KumlToolRegistry.resetExternalCacheForTest()

            val first = KumlToolRegistry.discoverExternal()
            val second = KumlToolRegistry.discoverExternal()
            second.size shouldBe first.size
        }
    })
