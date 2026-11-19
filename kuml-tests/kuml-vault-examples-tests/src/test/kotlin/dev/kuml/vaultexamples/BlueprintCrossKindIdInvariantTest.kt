@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 121d: no vault Service Blueprint example reports `KUML-BP-W002` (one id used by several element kinds).
 */
class BlueprintCrossKindIdInvariantTest :
    FunSpec({
        val examples = VaultExampleLoader.loadFromClasspath().filter { it.kumlScript.contains("blueprint(") }

        test("the vault contains blueprint examples") {
            (examples.size >= 4) shouldBe true
        }

        for (ex in examples) {
            test("example ${ex.fileName}: no cross-kind id collision") {
                VaultExampleRenderer.blueprintCrossKindIds(ex.kumlScript) shouldBe emptyList()
            }
        }
    })
