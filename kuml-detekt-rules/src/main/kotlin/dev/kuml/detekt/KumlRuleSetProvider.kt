package dev.kuml.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class KumlRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("kuml")

    // Detekt 2.0 takes rule *constructor references*, not instances.
    override fun instance() = RuleSet(ruleSetId, listOf(::RequireNamedArguments))
}
