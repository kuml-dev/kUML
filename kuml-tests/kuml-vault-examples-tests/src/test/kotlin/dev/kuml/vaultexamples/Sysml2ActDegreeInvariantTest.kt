@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.sysml2.constraint.ActSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Nacharbeit 121a: the activity flow-degree rules ACT322 to ACT326 (initial with incoming flow, final with outgoing flow,
 * call-behavior / send-signal with several outgoing flows, decision with one branch, merge with one incoming flow) never
 * fire on a vault example; no vault activity example reports an error either.
 */
class Sysml2ActDegreeInvariantTest :
    FunSpec({
        val actExamples = VaultExampleLoader.loadFromClasspath().filter { it.kumlScript.contains("actDiagram") }

        test("the vault contains activity examples") {
            (actExamples.size >= 4) shouldBe true
        }

        for (ex in actExamples) {
            test("example ${ex.fileName}: no ACT322-ACT326 warning and no activity error") {
                val findings = VaultExampleRenderer.actCheckerFindings(ex.kumlScript)
                findings.filter { it.first in 322..326 } shouldBe emptyList()
                findings.filter { it.second == ActSeverity.Error } shouldBe emptyList()
            }
        }
    })
