package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.erm.ErmReverseResult
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.RelationshipKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * End-to-end test parsing `sql-samples/ecommerce.sql` — mirrors the shape of
 * `kuml-cli/src/test/resources/erm/valid-ecommerce.kuml.kts` so the SQL→ERM
 * reverse path and the hand-written DSL fixture exercise the same element mix.
 */
class PostgresErmReverseEngineTest :
    FunSpec({

        val engine = PostgresErmReverseEngine()

        fun fixture(name: String): Path {
            val url =
                PostgresErmReverseEngineTest::class.java.classLoader.getResource("sql-samples/$name")
                    ?: error("fixture '$name' not found")
            return Path.of(url.toURI())
        }

        val result =
            runBlocking {
                engine.analyze(ReverseRequest(sourceRoots = listOf(fixture("ecommerce.sql")), targetModelName = "ECommerce"))
            }

        test("analysis succeeds") {
            result.shouldBeInstanceOf<ErmReverseResult.Success>()
        }

        val success = result as ErmReverseResult.Success
        val model = success.model

        test("four entities are produced") {
            model.entities shouldHaveSize 4
            model.entities.map { it.name }.toSet() shouldBe setOf("customers", "orders", "products", "order_items")
        }

        test("customers.id is a UUID primary key with a function-call default") {
            val customers = model.entities.first { it.name == "customers" }
            val id = customers.attributeByName("id")!!
            id.type shouldBe ErmDataType.Uuid
            id.primaryKey shouldBe true
            id.nullable shouldBe false
            id.default shouldBe "gen_random_uuid ()"
        }

        test("customers.email is unique and not null") {
            val customers = model.entities.first { it.name == "customers" }
            val email = customers.attributeByName("email")!!
            email.type shouldBe ErmDataType.Varchar(255)
            email.unique shouldBe true
            email.nullable shouldBe false
        }

        test("orders.id is SERIAL mapped to Integer(32) with autoIncrement") {
            val orders = model.entities.first { it.name == "orders" }
            val id = orders.attributeByName("id")!!
            id.type shouldBe ErmDataType.Integer(32)
            id.autoIncrement shouldBe true
            id.primaryKey shouldBe true
        }

        test("orders.status has a quoted literal default") {
            val orders = model.entities.first { it.name == "orders" }
            orders.attributeByName("status")!!.default shouldBe "'pending'"
        }

        test("named table-level foreign key resolves against the target's primary key") {
            val orders = model.entities.first { it.name == "orders" }
            val customerId = orders.attributeByName("customer_id")!!
            val fk = customerId.foreignKey
            fk shouldNotBe null
            fk!!.targetAttributeId shouldBe null // canonical: references the target's sole PK
            val customers = model.entities.first { it.name == "customers" }
            fk.targetEntityId shouldBe customers.id
        }

        test("named table-level CHECK constraint is captured") {
            val orders = model.entities.first { it.name == "orders" }
            orders.checks shouldHaveSize 1
            orders.checks[0].expression shouldBe "total >= 0"
            orders.checks[0].name shouldBe "chk_orders_total"
        }

        test("composite PRIMARY KEY junction table becomes a weak entity with two identifying relationships") {
            val orderItems = model.entities.first { it.name == "order_items" }
            orderItems.weak shouldBe true
            orderItems.attributeByName("order_id")!!.primaryKey shouldBe true
            orderItems.attributeByName("product_id")!!.primaryKey shouldBe true
            orderItems.attributeByName("order_id")!!.foreignKey shouldNotBe null
            orderItems.attributeByName("product_id")!!.foreignKey shouldNotBe null

            val incoming = model.relationships.filter { it.targetEntityId == orderItems.id }
            incoming shouldHaveSize 2
            incoming.forEach { rel ->
                rel.kind shouldBe RelationshipKind.IDENTIFYING
                rel.sourceCardinality shouldBe Cardinality.ONE
                rel.targetCardinality shouldBe Cardinality.ZERO_MANY
            }
        }

        test("CREATE INDEX and CREATE UNIQUE INDEX are captured on the target entity") {
            val orders = model.entities.first { it.name == "orders" }
            orders.indexes shouldHaveSize 2
            val simple = orders.indexes.first { !it.unique }
            simple.attributeIds shouldBe listOf(orders.attributeByName("customer_id")!!.id)
            val unique = orders.indexes.first { it.unique }
            unique.attributeIds shouldBe
                listOf(orders.attributeByName("customer_id")!!.id, orders.attributeByName("status")!!.id)
        }

        test("CREATE VIEW resolves its referenced entity") {
            model.views shouldHaveSize 1
            val view = model.views[0]
            view.name shouldBe "big_orders"
            val orders = model.entities.first { it.name == "orders" }
            view.referencedEntityIds shouldBe listOf(orders.id)
        }

        test("diagnostics report no ERROR-level entries") {
            model.entities shouldNotBe emptyList<Any>() // sanity: model is non-trivial
            success.diagnostics.none { it.severity == ReverseDiagnostic.Severity.ERROR } shouldBe true
        }
    })
