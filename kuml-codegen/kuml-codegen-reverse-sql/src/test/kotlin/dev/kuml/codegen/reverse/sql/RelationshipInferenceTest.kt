package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.erm.ErmReverseResult
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.RelationshipKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * Covers direction + cardinality inference — the plan's `RelationshipInferenceTest`
 * bullet: `sourceEntityId`/`targetEntityId` direction, `FK ∈ PK ⇒ IDENTIFYING +
 * weak=true`, nullable FK ⇒ optional parent (`Cardinality.ZERO_ONE` source side),
 * `NOT NULL` + `UNIQUE` FK ⇒ 1:1.
 *
 * NOTE: fixtures use "supplier"/"product" (parent/child) rather than literal
 * "parent"/"child" table names — JSqlParser 5.3's grammar treats "parent" as a
 * reserved word in some contexts (e.g. right after `REFERENCES`) and fails to
 * parse it as a plain identifier there.
 */
class RelationshipInferenceTest :
    FunSpec({

        val engine = PostgresErmReverseEngine()

        fun analyze(sql: String): ErmModel {
            val dir = Files.createTempDirectory("rel-inference-")
            Files.writeString(dir.resolve("schema.sql"), sql)
            val request = ReverseRequest(sourceRoots = listOf(dir), includeGlobs = listOf("**/*.sql"))
            val result = runBlocking { engine.analyze(request) }
            result.shouldBeInstanceOf<ErmReverseResult.Success>()
            return result.model
        }

        test("direction: sourceEntityId is the parent (PK side), targetEntityId is the child (FK side)") {
            val model =
                analyze(
                    """
                    CREATE TABLE supplier (id INT PRIMARY KEY);
                    CREATE TABLE product (supplier_id INT NOT NULL REFERENCES supplier(id));
                    """.trimIndent(),
                )
            val supplier = model.entities.first { it.name == "supplier" }
            val product = model.entities.first { it.name == "product" }
            model.relationships shouldHaveSize 1
            model.relationships[0].sourceEntityId shouldBe supplier.id
            model.relationships[0].targetEntityId shouldBe product.id
        }

        test("plain non-unique FK ⇒ NON_IDENTIFYING, ordinary 1:N (ZERO_MANY target side)") {
            val model =
                analyze(
                    """
                    CREATE TABLE supplier (id INT PRIMARY KEY);
                    CREATE TABLE product (supplier_id INT NOT NULL REFERENCES supplier(id));
                    """.trimIndent(),
                )
            val rel = model.relationships[0]
            rel.kind shouldBe RelationshipKind.NON_IDENTIFYING
            rel.targetCardinality shouldBe Cardinality.ZERO_MANY
        }

        test("nullable FK ⇒ optional parent (ZERO_ONE on the source side)") {
            val model =
                analyze(
                    """
                    CREATE TABLE supplier (id INT PRIMARY KEY);
                    CREATE TABLE product (supplier_id INT REFERENCES supplier(id));
                    """.trimIndent(),
                )
            model.relationships[0].sourceCardinality shouldBe Cardinality.ZERO_ONE
        }

        test("NOT NULL FK ⇒ mandatory parent (ONE on the source side)") {
            val model =
                analyze(
                    """
                    CREATE TABLE supplier (id INT PRIMARY KEY);
                    CREATE TABLE product (supplier_id INT NOT NULL REFERENCES supplier(id));
                    """.trimIndent(),
                )
            model.relationships[0].sourceCardinality shouldBe Cardinality.ONE
        }

        test("NOT NULL + UNIQUE FK models 1:1 (ZERO_ONE on the target side)") {
            val model =
                analyze(
                    """
                    CREATE TABLE supplier (id INT PRIMARY KEY);
                    CREATE TABLE product (supplier_id INT NOT NULL UNIQUE REFERENCES supplier(id));
                    """.trimIndent(),
                )
            val rel = model.relationships[0]
            rel.sourceCardinality shouldBe Cardinality.ONE
            rel.targetCardinality shouldBe Cardinality.ZERO_ONE
        }

        test("FK that is the child's sole primary key ⇒ IDENTIFYING + weak=true, 1:1 (ZERO_ONE target side)") {
            val model =
                analyze(
                    """
                    CREATE TABLE supplier (id INT PRIMARY KEY);
                    CREATE TABLE product (id INT PRIMARY KEY REFERENCES supplier(id));
                    """.trimIndent(),
                )
            val product = model.entities.first { it.name == "product" }
            product.weak shouldBe true
            val rel = model.relationships[0]
            rel.kind shouldBe RelationshipKind.IDENTIFYING
            rel.targetCardinality shouldBe Cardinality.ZERO_ONE
        }

        test("FK that is one of several composite PK columns (junction shape) ⇒ IDENTIFYING, ZERO_MANY target side") {
            val model =
                analyze(
                    """
                    CREATE TABLE a (id INT PRIMARY KEY);
                    CREATE TABLE b (id INT PRIMARY KEY);
                    CREATE TABLE junction (
                        a_id INT NOT NULL REFERENCES a(id),
                        b_id INT NOT NULL REFERENCES b(id),
                        PRIMARY KEY (a_id, b_id)
                    );
                    """.trimIndent(),
                )
            val junction = model.entities.first { it.name == "junction" }
            junction.weak shouldBe true
            model.relationships shouldHaveSize 2
            model.relationships.forEach { rel ->
                rel.kind shouldBe RelationshipKind.IDENTIFYING
                rel.targetCardinality shouldBe Cardinality.ZERO_MANY
            }
        }
    })
