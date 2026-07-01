package dev.kuml.uml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Metamodel tests for [UmlCollaboration] and [UmlCollaborationRole] (AP-3.5.1).
 */
class CollaborationModelTest :
    FunSpec(body = {

        val json =
            Json {
                prettyPrint = true
                encodeDefaults = true
            }

        // ── Test 1: empty collaboration ──────────────────────────────────────────

        test("empty collaboration builds with required fields only") {
            val collab = UmlCollaboration(id = "OrderPlacement", name = "OrderPlacement")
            collab.id shouldBe "OrderPlacement"
            collab.name shouldBe "OrderPlacement"
            collab.visibility shouldBe Visibility.PUBLIC
            collab.roles.shouldBeEmpty()
            collab.stereotypes.shouldBeEmpty()
            collab.appliedStereotypes.shouldBeEmpty()
            collab.metadata.isEmpty() shouldBe true
        }

        // ── Test 2: collaboration with roles ─────────────────────────────────────

        test("collaboration with two roles stores roles correctly") {
            val buyerRole =
                UmlCollaborationRole(
                    id = "OrderPlacement::buyer",
                    name = "buyer",
                    type = UmlTypeRef(name = "Customer"),
                )
            val sellerRole =
                UmlCollaborationRole(
                    id = "OrderPlacement::seller",
                    name = "seller",
                    type = UmlTypeRef(name = "Merchant"),
                    multiplicity = Multiplicity(lower = 1, upper = null),
                )
            val collab =
                UmlCollaboration(
                    id = "OrderPlacement",
                    name = "OrderPlacement",
                    roles = listOf(buyerRole, sellerRole),
                )
            collab.roles shouldHaveSize 2
            collab.roles[0].name shouldBe "buyer"
            collab.roles[0].type.name shouldBe "Customer"
            collab.roles[1].name shouldBe "seller"
            collab.roles[1].multiplicity.upper shouldBe null
        }

        // ── Test 3: UmlCollaboration is UmlClassifier and Stereotypable ──────────

        test("UmlCollaboration implements UmlClassifier and Stereotypable") {
            val collab = UmlCollaboration(id = "C", name = "C")
            collab.shouldBeInstanceOf<UmlClassifier>()
            collab.shouldBeInstanceOf<UmlNamedElement>()
            collab.shouldBeInstanceOf<UmlElement>()
            collab.shouldBeInstanceOf<Stereotypable>()
        }

        // ── Test 4: Serialisation round-trip ─────────────────────────────────────

        test("UmlCollaboration round-trips through JSON") {
            val before =
                UmlCollaboration(
                    id = "sale::OrderPlacement",
                    name = "OrderPlacement",
                    visibility = Visibility.PUBLIC,
                    roles =
                        listOf(
                            UmlCollaborationRole(
                                id = "sale::OrderPlacement::buyer",
                                name = "buyer",
                                type = UmlTypeRef(name = "Customer"),
                            ),
                            UmlCollaborationRole(
                                id = "sale::OrderPlacement::seller",
                                name = "seller",
                                type = UmlTypeRef(name = "Merchant"),
                                multiplicity = Multiplicity(lower = 1, upper = 1),
                            ),
                        ),
                )
            val text = json.encodeToString(before)
            val after = json.decodeFromString<UmlCollaboration>(text)
            after shouldBe before
        }

        // ── Test 5: applied stereotype smoke-test ────────────────────────────────

        test("collaboration accepts an applied stereotype (smoke)") {
            // Use a simple inline AppliedStereotype to avoid kuml-profile-api dependency here
            val stubApp =
                object : AppliedStereotype {
                    override val profileNamespace = "dev.test"
                    override val stereotypeName = "ServiceContract"
                    override val tags = emptyMap<String, TagValue>()
                }
            val collab =
                UmlCollaboration(
                    id = "TradeExecution",
                    name = "TradeExecution",
                    appliedStereotypes = listOf(stubApp),
                )
            collab.appliedStereotypes shouldHaveSize 1
            collab.appliedStereotypes[0].stereotypeName shouldBe "ServiceContract"
        }
    })
