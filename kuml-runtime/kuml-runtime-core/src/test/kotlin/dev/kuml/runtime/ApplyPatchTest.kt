package dev.kuml.runtime

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.ocl.OclCheckResult
import dev.kuml.core.ocl.OclSyntax
import dev.kuml.runtime.snapshot.MigrationPolicy
import dev.kuml.runtime.snapshot.fingerprint
import dev.kuml.uml.TransitionMetadataKeys
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for [applyPatch] / [ModelPatch.ChangeGuard] (Wave 3).
 *
 * Fixture: `init -> A --e[guard]--> B(final)` with a non-protected transition
 * `t1` and a protected transition `p1`, both `A -> B` on distinct triggers.
 */
class ApplyPatchTest :
    FunSpec({
        val protectedTransition =
            UmlTransition(
                id = "p1",
                sourceId = "A",
                targetId = "B",
                trigger = "p",
                guard = "false",
                metadata = mapOf(TransitionMetadataKeys.PROTECTED to KumlMetaValue.Flag(true)),
            )

        val model =
            smOf(
                name = "PatchSM",
                vertices = listOf(initial("init"), state("A"), finalState("B")),
                transitions =
                    listOf(
                        trans("t0", "init", "A"),
                        trans("t1", "A", "B", trigger = "e", guard = "false"),
                        protectedTransition,
                    ),
            )

        val runtime = StateMachineRuntime()

        test("happy path — valid guard edit on non-protected transition is applied") {
            val instance = runtime.start(model)
            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "true"))

            result.shouldBeInstanceOf<PatchResult.Applied>()
            result.model.transitions
                .first { it.id == "t1" }
                .guard shouldBe "true"
            result.instance.currentVertexIds shouldBe instance.currentVertexIds
            result.instance.variables shouldBe instance.variables
            result.instance.trace shouldBe instance.trace
            result.instance.seqCounter shouldBe instance.seqCounter
        }

        test("atomicity — original instance/model are never mutated") {
            val instance = runtime.start(model)
            val originalGuard =
                instance.model.transitions
                    .first { it.id == "t1" }
                    .guard
            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "true"))

            result.shouldBeInstanceOf<PatchResult.Applied>()
            instance.model.transitions
                .first { it.id == "t1" }
                .guard shouldBe originalGuard
            result.instance shouldNotBe instance
        }

        test("blank guard normalizes to null (clears the guard)") {
            val instance = runtime.start(model)

            val resultEmpty = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", ""))
            resultEmpty.shouldBeInstanceOf<PatchResult.Applied>()
            resultEmpty.model.transitions
                .first { it.id == "t1" }
                .guard
                .shouldBeNull()

            val resultBlank = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "   "))
            resultBlank.shouldBeInstanceOf<PatchResult.Applied>()
            resultBlank.model.transitions
                .first { it.id == "t1" }
                .guard
                .shouldBeNull()
        }

        test("invalid OCL syntax is rejected with a localized range") {
            val instance = runtime.start(model)
            val originalGuard =
                instance.model.transitions
                    .first { it.id == "t1" }
                    .guard

            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "vars.count >"))

            result.shouldBeInstanceOf<PatchResult.Rejected.InvalidOcl>()
            result.error.range.shouldNotBeNull()
            instance.model.transitions
                .first { it.id == "t1" }
                .guard shouldBe originalGuard
        }

        test("oversized expression is rejected by the static size guard") {
            val instance = runtime.start(model)
            val huge = "1" + "+1".repeat(3000)

            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", huge))

            result.shouldBeInstanceOf<PatchResult.Rejected.InvalidOcl>()
            result.error.message shouldContain "too long"
        }

        test("unknown transition id is rejected") {
            val instance = runtime.start(model)
            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("nope", "true"))
            result.shouldBeInstanceOf<PatchResult.Rejected.TransitionNotFound>()
            result.transitionId shouldBe "nope"
        }

        test("protected transition without confirmation is rejected") {
            val instance = runtime.start(model)
            val originalGuard =
                instance.model.transitions
                    .first { it.id == "p1" }
                    .guard

            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("p1", "true"), confirmed = false)

            result.shouldBeInstanceOf<PatchResult.Rejected.ProtectedNotConfirmed>()
            instance.model.transitions
                .first { it.id == "p1" }
                .guard shouldBe originalGuard
        }

        test("protected transition with confirmation is applied") {
            val instance = runtime.start(model)
            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("p1", "true"), confirmed = true)

            result.shouldBeInstanceOf<PatchResult.Applied>()
            result.model.transitions
                .first { it.id == "p1" }
                .guard shouldBe "true"
        }

        test("non-protected transition ignores confirmed=false") {
            val instance = runtime.start(model)
            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "true"), confirmed = false)
            result.shouldBeInstanceOf<PatchResult.Applied>()
        }

        test("default policy (Reject) permits a guard-only edit") {
            val instance = runtime.start(model)
            // Default policy argument is MigrationPolicy.Reject; not passed explicitly to pin the default.
            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "true"))
            result.shouldBeInstanceOf<PatchResult.Applied>()
        }

        test("explicit MigrationPolicy.Reject also permits a guard-only edit") {
            val instance = runtime.start(model)
            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "true"), policy = MigrationPolicy.Reject)
            result.shouldBeInstanceOf<PatchResult.Applied>()
        }

        test("a patched guard is live — flipping false to true makes the transition fire") {
            val instance = runtime.start(model)
            // Guard starts as "false" — event 'e' should not transition.
            val stayed = runtime.step(instance, Event.of("e"))
            stayed.shouldBeInstanceOf<StepResult.Stayed>()

            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "true"))
            result.shouldBeInstanceOf<PatchResult.Applied>()
            val newInstance = result.instance

            // B is a final state — firing t1 terminates the machine (Rule 9), so the
            // live-ness of the patched guard is observed via isTerminated + currentVertexIds
            // rather than StepResult.Transitioned.
            val stepResult = runtime.step(newInstance, Event.of("e"))
            stepResult.shouldBeInstanceOf<StepResult.Terminated>()
            newInstance.currentVertexIds shouldBe listOf("B")
            newInstance.isTerminated shouldBe true
        }

        test("defaultGuardScope() is exactly the scope applyPatch's static check uses (drift guard)") {
            // applyPatch's static gate calls OclSyntax.typeCheck(normalized, defaultGuardScope())
            // directly (see ModelPatch.kt) — this pins that behaviorally so a future refactor
            // that reintroduces a private/divergent scope inside applyPatch breaks loudly here,
            // rather than silently letting the editor's live type-check and applyPatch's static
            // check disagree.
            val instance = runtime.start(model)

            // "vars.ready" only type-checks because "vars" is in defaultGuardScope() ...
            OclSyntax.typeCheck("vars.ready", defaultGuardScope()).shouldBeInstanceOf<OclCheckResult.Ok>()
            val accepted = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "vars.ready"))
            accepted.shouldBeInstanceOf<PatchResult.Applied>()

            // ... while "nope.ready" fails under defaultGuardScope() because "nope" isn't a scope variable.
            OclSyntax.typeCheck("nope.ready", defaultGuardScope()).shouldBeInstanceOf<OclCheckResult.Error>()
            val rejected = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "nope.ready"))
            rejected.shouldBeInstanceOf<PatchResult.Rejected.InvalidOcl>()
        }

        test("guard edits are fingerprint-transparent") {
            val instance = runtime.start(model)
            val before = fingerprint(instance.model)
            val result = runtime.applyPatch(instance, ModelPatch.ChangeGuard("t1", "true"))
            result.shouldBeInstanceOf<PatchResult.Applied>()
            val after = fingerprint(result.model)
            after shouldBe before
        }
    })
