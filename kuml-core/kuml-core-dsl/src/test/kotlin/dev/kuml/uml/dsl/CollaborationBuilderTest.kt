package dev.kuml.uml.dsl

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.dsl.compositeStructureDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlCollaboration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** Convenience accessor to flatten KumlModel element list. */
private val KumlModel.elements
    get() = (root as KumlDiagram).elements

/**
 * DSL builder tests for [CollaborationBuilder] / [CollaborationRoleBuilder] (AP-3.5.2).
 */
class CollaborationBuilderTest :
    FunSpec(body = {

        beforeEach { ProfileRegistry.clear() }

        // ── Test 1: empty collaboration ──────────────────────────────────────────

        test("empty collaboration builds with no roles") {
            val model =
                umlModel("M") {
                    collaboration("OrderPlacement")
                }
            val collabs = model.elements.filterIsInstance<UmlCollaboration>()
            collabs shouldHaveSize 1
            collabs[0].name shouldBe "OrderPlacement"
            collabs[0].roles.shouldBeEmpty()
            collabs[0].appliedStereotypes.shouldBeEmpty()
        }

        // ── Test 2: collaboration with two roles ─────────────────────────────────

        test("collaboration with two roles stores roles in order") {
            val model =
                umlModel("M") {
                    collaboration("OrderPlacement") {
                        role("buyer", type = "Customer")
                        role("seller", type = "Merchant")
                    }
                }
            val collab = model.elements.filterIsInstance<UmlCollaboration>().single()
            collab.roles shouldHaveSize 2
            collab.roles[0].name shouldBe "buyer"
            collab.roles[0].type.name shouldBe "Customer"
            collab.roles[1].name shouldBe "seller"
            collab.roles[1].type.name shouldBe "Merchant"
        }

        // ── Test 3: role with custom multiplicity ────────────────────────────────

        test("role stores custom multiplicity") {
            val model =
                umlModel("M") {
                    collaboration("Fulfillment") {
                        role(
                            "warehouse",
                            type = "Warehouse",
                            multiplicity = Multiplicity(lower = 1, upper = null),
                        )
                    }
                }
            val collab = model.elements.filterIsInstance<UmlCollaboration>().single()
            val role = collab.roles.single()
            role.multiplicity shouldBe Multiplicity(lower = 1, upper = null)
        }

        // ── Test 4: stereotype on collaboration ──────────────────────────────────

        test("stereotype can be applied to a collaboration") {
            val soamlProfile =
                profile("SoaML") {
                    namespace = "dev.kuml.test.soaml"
                    stereotype("ServiceContract") {
                        extends(UmlMetaclass.Collaboration)
                    }
                }
            ProfileRegistry.register(soamlProfile)

            val model =
                umlModel("M") {
                    applyProfile(soamlProfile)
                    collaboration("TradeExecution") {
                        stereotype("ServiceContract")
                    }
                }
            val collab = model.elements.filterIsInstance<UmlCollaboration>().single()
            collab.appliedStereotypes shouldHaveSize 1
            val app = collab.appliedStereotypes[0] as KumlStereotypeApplication
            app.stereotypeName shouldBe "ServiceContract"
            app.profileNamespace shouldBe "dev.kuml.test.soaml"
        }

        // ── Test 5: stereotype on role ───────────────────────────────────────────

        test("stereotype can be applied to a collaboration role") {
            val roleProfile =
                profile("Roles") {
                    namespace = "dev.kuml.test.roles"
                    stereotype("Initiator") {
                        extends(UmlMetaclass.Property)
                    }
                }
            ProfileRegistry.register(roleProfile)

            val model =
                umlModel("M") {
                    applyProfile(roleProfile)
                    collaboration("OrderPlacement") {
                        role("buyer", type = "Customer") {
                            stereotype("Initiator")
                        }
                    }
                }
            val collab = model.elements.filterIsInstance<UmlCollaboration>().single()
            val role = collab.roles.single()
            role.appliedStereotypes shouldHaveSize 1
            val app = role.appliedStereotypes[0] as KumlStereotypeApplication
            app.stereotypeName shouldBe "Initiator"
        }

        // ── Test 6: collaboration in classDiagram ────────────────────────────────

        test("collaboration is accepted in a class diagram") {
            val d =
                classDiagram("CD") {
                    collaboration("PaymentProtocol") {
                        role("payer", type = "Customer")
                    }
                }
            val collab = d.elements.filterIsInstance<UmlCollaboration>().single()
            collab.name shouldBe "PaymentProtocol"
        }

        // ── Test 7: collaboration in compositeStructureDiagram ───────────────────

        test("collaboration is accepted in a composite-structure diagram") {
            val d =
                compositeStructureDiagram("CS") {
                    collaboration("PaymentProtocol")
                }
            d.elements.filterIsInstance<UmlCollaboration>() shouldHaveSize 1
        }

        // ── Test 8: generated IDs are stable and unique ──────────────────────────

        test("generated IDs are stable and unique within the model") {
            val model =
                umlModel("M") {
                    collaboration("A")
                    collaboration("B")
                }
            val collabs = model.elements.filterIsInstance<UmlCollaboration>()
            collabs shouldHaveSize 2
            val ids = collabs.map { it.id }.toSet()
            ids shouldHaveSize 2
        }
    })
