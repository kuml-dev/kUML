package dev.kuml.runtime

import dev.kuml.runtime.internal.SYNTHETIC_ROOT_ID
import dev.kuml.runtime.internal.buildParentOf
import dev.kuml.runtime.internal.lowestCommonAncestor
import dev.kuml.runtime.internal.pathUpTo
import dev.kuml.runtime.internal.triggerName
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HierarchyHelpersTest :
    FunSpec({

        test("triggerName extracts identifier before parenthesis") {
            triggerName("confirm(amount)") shouldBe "confirm"
            triggerName("submit") shouldBe "submit"
            triggerName(null) shouldBe null
            triggerName("  ") shouldBe null
            triggerName("") shouldBe null
            triggerName("  confirm  ") shouldBe "confirm"
        }

        test("buildParentOf maps top-level vertices to SYNTHETIC_ROOT_ID") {
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "SM",
                    vertices =
                        listOf(
                            UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL),
                            UmlState(id = "A", name = "A"),
                            UmlState(id = "B", name = "B"),
                        ),
                )
            val p = buildParentOf(sm)
            p["init"] shouldBe SYNTHETIC_ROOT_ID
            p["A"] shouldBe SYNTHETIC_ROOT_ID
            p["B"] shouldBe SYNTHETIC_ROOT_ID
        }

        test("buildParentOf handles composite states recursively") {
            val sm =
                UmlStateMachine(
                    id = "sm",
                    name = "SM",
                    vertices =
                        listOf(
                            UmlState(
                                id = "Outer",
                                name = "Outer",
                                substates =
                                    listOf(
                                        UmlState(id = "Inner1", name = "Inner1"),
                                        UmlState(
                                            id = "Inner2",
                                            name = "Inner2",
                                            substates = listOf(UmlState(id = "Leaf", name = "Leaf")),
                                        ),
                                    ),
                            ),
                        ),
                )
            val p = buildParentOf(sm)
            p["Outer"] shouldBe SYNTHETIC_ROOT_ID
            p["Inner1"] shouldBe "Outer"
            p["Inner2"] shouldBe "Outer"
            p["Leaf"] shouldBe "Inner2"
        }

        test("lowestCommonAncestor finds common parent state") {
            val parentOf = mapOf("A" to "Outer", "B" to "Outer", "Outer" to SYNTHETIC_ROOT_ID)
            lowestCommonAncestor("A", "B", parentOf) shouldBe "Outer"
        }

        test("lowestCommonAncestor returns synthetic root for top-level siblings") {
            val parentOf = mapOf("A" to SYNTHETIC_ROOT_ID, "B" to SYNTHETIC_ROOT_ID)
            lowestCommonAncestor("A", "B", parentOf) shouldBe SYNTHETIC_ROOT_ID
        }

        test("pathUpTo stops at given ancestor (exclusive)") {
            val parentOf =
                mapOf("Leaf" to "Inner2", "Inner2" to "Outer", "Outer" to SYNTHETIC_ROOT_ID)
            pathUpTo("Leaf", "Outer", parentOf) shouldBe listOf("Leaf", "Inner2")
            pathUpTo("Outer", SYNTHETIC_ROOT_ID, parentOf) shouldBe listOf("Outer")
        }
    })
