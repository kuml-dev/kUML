package dev.kuml.sysml2.projection

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** PAL wave E1 (13e): the `flows` compartment no longer repeats a port line. */
class BddFlowsRepeatingPortTest :
    FunSpec({
        test("a flow that only repeats the port line is dropped, the compartment stays empty") {
            dropFlowsRepeatingFeatures(
                flows = listOf("in p.p : T"),
                featureLines = listOf("in p : T"),
            ).shouldBeEmpty()
        }

        test("a word-for-word equal line is dropped") {
            dropFlowsRepeatingFeatures(flows = listOf("in p : T"), featureLines = listOf("in  p : T")).shouldBeEmpty()
        }

        test("another type, direction or item name stays") {
            val flows = listOf("in p.p : U", "out p.p : T", "in p.q : T")
            dropFlowsRepeatingFeatures(flows = flows, featureLines = listOf("in p : T")) shouldBe flows
        }

        test("only the repeated line is removed from a longer list") {
            dropFlowsRepeatingFeatures(
                flows = listOf("in p.p : T", "out p.q : U"),
                featureLines = listOf("in p : T"),
            ) shouldBe listOf("out p.q : U")
        }
    })
