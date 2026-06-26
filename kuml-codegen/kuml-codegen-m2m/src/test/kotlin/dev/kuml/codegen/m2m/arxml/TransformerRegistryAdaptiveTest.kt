package dev.kuml.codegen.m2m.arxml

import dev.kuml.codegen.m2m.TransformerRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * Verifies that [ArxmlToSysml2Transformer] and [Sysml2ToArxmlTransformer]
 * are discovered via ServiceLoader by [TransformerRegistry.loadFromClasspath].
 *
 * V3.1.35 — initial implementation.
 */
class TransformerRegistryAdaptiveTest :
    FunSpec({

        beforeEach { TransformerRegistry.clear() }
        afterEach { TransformerRegistry.clear() }

        test("loadFromClasspath registers arxml-to-sysml2 and sysml2-to-arxml") {
            TransformerRegistry.loadFromClasspath()
            TransformerRegistry.ids() shouldContainAll listOf("arxml-to-sysml2", "sysml2-to-arxml")
        }

        test("get arxml-to-sysml2 returns non-null transformer after loadFromClasspath") {
            TransformerRegistry.loadFromClasspath()
            TransformerRegistry.get<Any, Any>("arxml-to-sysml2").shouldNotBeNull()
        }

        test("get sysml2-to-arxml returns non-null transformer after loadFromClasspath") {
            TransformerRegistry.loadFromClasspath()
            TransformerRegistry.get<Any, Any>("sysml2-to-arxml").shouldNotBeNull()
        }
    })
