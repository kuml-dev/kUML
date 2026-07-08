package dev.kuml.erm.constraint

import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.RelationshipKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Constraint-checker tests for V3.4.1 — one positive and one negative case
 * per rule (1–15), plus a full valid E-Commerce schema exercising every
 * first-class element (weak entity, identifying relationship, view, index,
 * check constraint).
 */
class ErmConstraintCheckerTest :
    StringSpec({
        val checker = ErmConstraintChecker()

        fun validEcommerceSchema(): ErmModel =
            ermModel("ECommerce") {
                val customer =
                    entity("Customer") {
                        id()
                        attribute(name = "email", type = ErmDataType.Varchar(255), unique = true)
                        index("email", unique = true, name = "idx_customer_email")
                    }
                val order =
                    entity("Order") {
                        id()
                        foreignKey(name = "customer_id", references = customer)
                        attribute(name = "total", type = ErmDataType.Decimal(10, 2))
                        check(expression = "total >= 0", name = "non_negative_total")
                    }
                val item =
                    entity("OrderItem", weak = true) {
                        foreignKey(name = "order_id", references = order)
                        attribute(name = "quantity", type = ErmDataType.Integer())
                    }
                relationship(from = customer, to = order, name = "places")
                relationship(from = order, to = item, name = "contains", kind = RelationshipKind.IDENTIFYING)
                view(name = "big_orders", query = "SELECT * FROM \"Order\" WHERE total > 100", references = listOf(order))
                diagram(name = "Overview", showIndexes = true)
            }

        fun errors(m: ErmModel) = checker.check(m).filter { it.severity == ViolationSeverity.ERROR }

        fun warnings(m: ErmModel) = checker.check(m).filter { it.severity == ViolationSeverity.WARNING }

        "valid E-Commerce schema has no violations" {
            checker.check(validEcommerceSchema()).shouldBeEmpty()
        }

        // ── 1. at least one entity ──
        "no entities → error" {
            errors(ErmModel(name = "x")).any { it.message.contains("no entities") }.shouldBeTrue()
        }
        "at least one entity → no rule-1 error" {
            val m = ermModel("x") { entity("A") { id() } }
            errors(m).none { it.message.contains("no entities") }.shouldBeTrue()
        }

        // ── 2. entity has at least one attribute ──
        "entity with no attributes → warning" {
            val m = ErmModel(name = "x", entities = listOf(ErmEntity(id = "e", name = "A")))
            warnings(m).any { it.message.contains("no attributes") }.shouldBeTrue()
        }
        "entity with attributes → no rule-2 warning" {
            val m = ermModel("x") { entity("A") { id() } }
            warnings(m).none { it.message.contains("no attributes") }.shouldBeTrue()
        }

        // ── 3. non-weak entity needs a primary key / weak entity needs identifying relationship ──
        "non-weak entity with no primary key → error" {
            val m = ermModel("x") { entity("A") { attribute(name = "x", type = ErmDataType.Text) } }
            errors(m).any { it.message.contains("no primary key") }.shouldBeTrue()
        }
        "weak entity without identifying relationship → error" {
            val m = ermModel("x") { entity("A", weak = true) { attribute(name = "x", type = ErmDataType.Text) } }
            errors(m).any { it.message.contains("not the target of an identifying relationship") }.shouldBeTrue()
        }
        "weak entity with identifying relationship → no rule-3 error" {
            val m = validEcommerceSchema()
            errors(m).none { it.message.contains("has no primary key") || it.message.contains("not the target of an identifying") }
                .shouldBeTrue()
        }

        // ── 4. unique attribute names within an entity ──
        "duplicate attribute name within entity → error" {
            val m =
                ermModel("x") {
                    entity("A") {
                        attribute(name = "dup", type = ErmDataType.Text)
                        attribute(name = "dup", type = ErmDataType.Text)
                    }
                }
            errors(m).any { it.message.contains("used more than once in entity") }.shouldBeTrue()
        }
        "unique attribute names → no rule-4 error" {
            errors(validEcommerceSchema()).none { it.message.contains("used more than once in entity") }.shouldBeTrue()
        }

        // ── 5. unique entity names ──
        "duplicate entity name → error" {
            val m =
                ermModel("x") {
                    entity("Dup") { id() }
                    entity("Dup") { id() }
                }
            errors(m).any { it.message.contains("used more than once in model") }.shouldBeTrue()
        }
        "unique entity names → no rule-5 error" {
            errors(validEcommerceSchema()).none { it.message.contains("used more than once in model") }.shouldBeTrue()
        }

        // ── 6. FK target entity/attribute exist ──
        "FK targeting unknown entity → error" {
            val m =
                ErmModel(
                    name = "x",
                    entities =
                        listOf(
                            ErmEntity(
                                id = "e0",
                                name = "A",
                                attributes =
                                    listOf(
                                        dev.kuml.erm.model.ErmAttribute(
                                            "a0",
                                            "fk",
                                            ErmDataType.Uuid,
                                            foreignKey = ErmForeignKey(targetEntityId = "does-not-exist"),
                                        ),
                                    ),
                            ),
                        ),
                )
            errors(m).any { it.message.contains("targets unknown entity") }.shouldBeTrue()
        }
        "FK targeting known entity → no rule-6 error" {
            errors(validEcommerceSchema()).none { it.message.contains("targets unknown entity") }.shouldBeTrue()
        }

        // ── 7. FK type compatibility ──
        "FK type mismatch with target PK → error" {
            val m =
                ermModel("x") {
                    val a = entity("A") { id(type = ErmDataType.Integer()) }
                    entity("B") {
                        attribute(
                            name = "a_id",
                            type = ErmDataType.Text,
                            foreignKey = ErmForeignKey(targetEntityId = a),
                        )
                    }
                }
            errors(m).any { it.message.contains("has type") && it.message.contains("target column has type") }.shouldBeTrue()
        }
        "FK type mismatch where either side is Custom → warning only" {
            val m =
                ermModel("x") {
                    val a = entity("A") { id(type = ErmDataType.Custom("SERIAL")) }
                    entity("B") {
                        attribute(
                            name = "a_id",
                            type = ErmDataType.Integer(),
                            foreignKey = ErmForeignKey(targetEntityId = a),
                        )
                    }
                }
            errors(m).none { it.message.contains("has type") }.shouldBeTrue()
            warnings(m).any { it.message.contains("has type") }.shouldBeTrue()
        }
        "FK type matches target PK → no rule-7 violation" {
            val v = checker.check(validEcommerceSchema())
            v.none { it.message.contains("has type") }.shouldBeTrue()
        }

        // ── 8. relationship endpoints exist ──
        "relationship with unknown sourceEntityId → error" {
            val m =
                ErmModel(
                    name = "x",
                    entities = listOf(ErmEntity(id = "e0", name = "A", attributes = emptyList())),
                    relationships =
                        listOf(
                            ErmRelationship(
                                id = "r0",
                                name = "rel",
                                sourceEntityId = "missing",
                                targetEntityId = "e0",
                                sourceCardinality = Cardinality.ONE,
                                targetCardinality = Cardinality.ZERO_MANY,
                            ),
                        ),
                )
            errors(m).any { it.message.contains("sourceEntityId 'missing' not found") }.shouldBeTrue()
        }
        "relationship with valid endpoints → no rule-8 error" {
            errors(validEcommerceSchema()).none { it.message.contains("not found") }.shouldBeTrue()
        }

        // ── 9. identifying relationship should target a weak entity (best practice) ──
        "identifying relationship targeting a non-weak entity → warning" {
            val m =
                ermModel("x") {
                    val a = entity("A") { id() }
                    val b = entity("B") { id() }
                    relationship(from = a, to = b, kind = RelationshipKind.IDENTIFYING)
                }
            warnings(m).any { it.message.contains("is not marked as weak") }.shouldBeTrue()
        }
        "identifying relationship targeting a weak entity → no rule-9 warning" {
            warnings(validEcommerceSchema()).none { it.message.contains("is not marked as weak") }.shouldBeTrue()
        }

        // ── 10. index attribute references ──
        "index with no attributes → error" {
            val m = ermModel("x") { entity("A") { id(); index() } }
            errors(m).any { it.message.contains("has no attributes") }.shouldBeTrue()
        }
        "index referencing attribute of a different entity → error" {
            val m =
                ErmModel(
                    name = "x",
                    entities =
                        listOf(
                            ErmEntity(id = "e0", name = "A", attributes = emptyList()),
                            ErmEntity(
                                id = "e1",
                                name = "B",
                                attributes = listOf(dev.kuml.erm.model.ErmAttribute("a1", "col", ErmDataType.Text)),
                                indexes = listOf(dev.kuml.erm.model.ErmIndex("i0", "bad", listOf("not-mine"))),
                            ),
                        ),
                )
            errors(m).any { it.message.contains("which is not part of entity") }.shouldBeTrue()
        }
        "index referencing own attributes → no rule-10 error" {
            errors(validEcommerceSchema()).none { it.message.contains("has no attributes") || it.message.contains("not part of entity") }
                .shouldBeTrue()
        }

        // ── 11. view query non-blank / referenced entities exist ──
        "view with blank query → error" {
            val m = ermModel("x") { entity("A") { id() }; view(name = "v", query = "") }
            errors(m).any { it.message.contains("empty query") }.shouldBeTrue()
        }
        "view referencing unknown entity → warning" {
            val m = ermModel("x") { entity("A") { id() }; view(name = "v", query = "SELECT 1", references = listOf("nope")) }
            warnings(m).any { it.message.contains("references unknown entity") }.shouldBeTrue()
        }
        "view with valid query and references → no rule-11 violation" {
            val v = checker.check(validEcommerceSchema())
            v.none { it.message.contains("empty query") || it.message.contains("view references unknown") }.shouldBeTrue()
        }

        // ── 12. diagram elementIds resolve ──
        "diagram referencing unknown element → error" {
            val m =
                ErmModel(
                    name = "x",
                    entities = listOf(ErmEntity(id = "e0", name = "A")),
                    diagrams = listOf(dev.kuml.erm.model.ErmDiagram(name = "D", elementIds = listOf("nope"))),
                )
            errors(m).any { it.message.contains("references unknown element") }.shouldBeTrue()
        }
        "diagram with empty elementIds (whole-model projection) → no rule-12 error" {
            errors(validEcommerceSchema()).none { it.message.contains("references unknown element") }.shouldBeTrue()
        }

        // ── 13. many-to-many identifying relationship ──
        "many-to-many identifying relationship → warning" {
            val m =
                ermModel("x") {
                    val a = entity("A") { id() }
                    val b = entity("B", weak = true) { id() }
                    relationship(
                        from = a,
                        to = b,
                        kind = RelationshipKind.IDENTIFYING,
                        sourceCardinality = Cardinality.ZERO_MANY,
                        targetCardinality = Cardinality.ZERO_MANY,
                    )
                }
            warnings(m).any { it.message.contains("missing junction-table resolution") }.shouldBeTrue()
        }
        "one-to-many identifying relationship → no rule-13 warning" {
            warnings(validEcommerceSchema()).none { it.message.contains("missing junction-table resolution") }.shouldBeTrue()
        }

        // ── 15. check-constraint expression non-blank ──
        "check constraint with blank expression → error" {
            val m =
                ErmModel(
                    name = "x",
                    entities =
                        listOf(
                            ErmEntity(
                                id = "e0",
                                name = "A",
                                attributes = listOf(dev.kuml.erm.model.ErmAttribute("a0", "col", ErmDataType.Text)),
                                checks = listOf(dev.kuml.erm.model.ErmCheckConstraint(id = "c0", name = "bad", expression = "")),
                            ),
                        ),
                )
            errors(m).any { it.message.contains("has an empty expression") }.shouldBeTrue()
        }
        "check constraint with non-blank expression → no rule-15 error" {
            errors(validEcommerceSchema()).none { it.message.contains("has an empty expression") }.shouldBeTrue()
        }

        // ── 14. autoIncrement only on Integer columns ──
        "autoIncrement on a non-integer column → warning" {
            val m =
                ermModel("x") {
                    entity("A") { attribute(name = "x", type = ErmDataType.Text, autoIncrement = true) }
                }
            warnings(m).any { it.message.contains("is autoIncrement but has non-integer type") }.shouldBeTrue()
        }
        "autoIncrement on an Integer column → no rule-14 warning" {
            val m =
                ermModel("x") {
                    entity("A") { attribute(name = "x", type = ErmDataType.Integer(), primaryKey = true, autoIncrement = true) }
                }
            warnings(m).none { it.message.contains("is autoIncrement but has non-integer type") }.shouldBeTrue()
        }
    })
