package dev.kuml.profile.soaml

import dev.kuml.core.ocl.OclValidator
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlCollaboration
import dev.kuml.uml.UmlCollaborationRole
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlPort
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * AP-4.4 — OCL constraint tests for SoaML stereotypes.
 *
 * Tests the programmatic evaluation of the two OCL constraints defined in
 * [soamlProfile]:
 * - `participant-has-port` on [UmlComponent] (Participant extends Class)
 * - `contract-has-two-roles` on [UmlCollaboration] (ServiceContract extends Collaboration)
 *
 * Uses [OclValidator.validateWithExpressions] to test constraint evaluation
 * without going through the CLI (AP-4.4 scope, not Ticket 6).
 *
 * Note: `UmlComponent` is used as the concrete `self` for Participant validation
 * because ports live on components in the kUML metamodel. The OCL property
 * `ownedPort` is mapped to `UmlComponent.ports` in [dev.kuml.core.ocl.UmlPropertyAccessor].
 */
class SoamlConstraintTest :
    FunSpec({

        val participantConstraints =
            soamlProfile
                .stereotype("Participant")!!
                .constraints
                .associate { it.name to it.body }

        val contractConstraints =
            soamlProfile
                .stereotype("ServiceContract")!!
                .constraints
                .associate { it.name to it.body }

        // ── Participant: participant-has-port ─────────────────────────────────────

        test("Participant constraint catches participant without ports") {
            val participantWithoutPorts =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports = emptyList(),
                )
            val result =
                OclValidator.validateWithExpressions(
                    self = participantWithoutPorts,
                    elementId = "OrderService",
                    elementName = "OrderService",
                    constraintBodies = participantConstraints,
                )
            result.valid shouldBe false
            result.violations.size shouldBe 1
            result.violations[0].constraintName shouldBe "participant-has-port"
        }

        test("Participant constraint passes when ports are present") {
            val participantWithPorts =
                UmlComponent(
                    id = "OrderService",
                    name = "OrderService",
                    ports =
                        listOf(
                            UmlPort(id = "OrderService::orders", name = "orders"),
                            UmlPort(id = "OrderService::payment", name = "payment"),
                        ),
                )
            val result =
                OclValidator.validateWithExpressions(
                    self = participantWithPorts,
                    elementId = "OrderService",
                    elementName = "OrderService",
                    constraintBodies = participantConstraints,
                )
            result.valid shouldBe true
            result.violations shouldBe emptyList()
        }

        // ── ServiceContract: contract-has-two-roles ───────────────────────────────

        test("ServiceContract constraint catches collaboration with fewer than 2 roles") {
            val contractWithOneRole =
                UmlCollaboration(
                    id = "OrderPaymentContract",
                    name = "OrderPaymentContract",
                    roles =
                        listOf(
                            UmlCollaborationRole(
                                id = "OrderPaymentContract::provider",
                                name = "provider",
                                type = UmlTypeRef(name = "OrderService"),
                                multiplicity = Multiplicity(),
                            ),
                        ),
                )
            val result =
                OclValidator.validateWithExpressions(
                    self = contractWithOneRole,
                    elementId = "OrderPaymentContract",
                    elementName = "OrderPaymentContract",
                    constraintBodies = contractConstraints,
                )
            result.valid shouldBe false
            result.violations.size shouldBe 1
            result.violations[0].constraintName shouldBe "contract-has-two-roles"
        }

        test("ServiceContract constraint passes with two or more roles") {
            val contractWithTwoRoles =
                UmlCollaboration(
                    id = "OrderPaymentContract",
                    name = "OrderPaymentContract",
                    roles =
                        listOf(
                            UmlCollaborationRole(
                                id = "OrderPaymentContract::provider",
                                name = "provider",
                                type = UmlTypeRef(name = "OrderService"),
                                multiplicity = Multiplicity(),
                            ),
                            UmlCollaborationRole(
                                id = "OrderPaymentContract::consumer",
                                name = "consumer",
                                type = UmlTypeRef(name = "PaymentService"),
                                multiplicity = Multiplicity(),
                            ),
                        ),
                )
            val result =
                OclValidator.validateWithExpressions(
                    self = contractWithTwoRoles,
                    elementId = "OrderPaymentContract",
                    elementName = "OrderPaymentContract",
                    constraintBodies = contractConstraints,
                )
            result.valid shouldBe true
            result.violations shouldBe emptyList()
        }
    })
