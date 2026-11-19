package dev.kuml.c4.constraint

import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Relationship
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class C4NearDuplicateRelationshipsTest :
    StringSpec({
        fun rel(
            id: String,
            source: String = "a",
            target: String = "b",
            label: String = "uses",
            technology: String? = null,
            description: String? = null,
            bidirectional: Boolean = false,
        ) = C4Relationship(
            id = id,
            source = source,
            target = target,
            label = label,
            technology = technology,
            bidirectional = bidirectional,
            description = description,
        )

        fun model(vararg rels: C4Relationship) = C4Model(id = "m", name = "M", relationships = rels.toList())

        fun near(vararg rels: C4Relationship) = C4NearDuplicateRelationships.findNear(model(*rels))

        "a case-only label difference gives one finding with count 2 and both ids" {
            val f = near(rel("r1", label = "Uses"), rel("r2", label = "uses"))
            f shouldHaveSize 1
            f.single().count shouldBe 2
            f.single().relationshipIds shouldBe listOf("r1", "r2")
        }
        "whitespace-only label differences are found" {
            near(rel("r1", label = " uses "), rel("r2", label = "uses")) shouldHaveSize 1
            near(rel("r1", label = "reads  data"), rel("r2", label = "reads data")) shouldHaveSize 1
            near(rel("r1", label = "reads\tdata"), rel("r2", label = "reads data")) shouldHaveSize 1
            near(rel("r1", label = "reads\ndata"), rel("r2", label = "reads data")) shouldHaveSize 1
        }
        "technology and description differences in case or whitespace are found" {
            near(rel("r1", technology = "HTTPS"), rel("r2", technology = "https")) shouldHaveSize 1
            near(rel("r1", technology = "REST  JSON"), rel("r2", technology = " REST JSON")) shouldHaveSize 1
            near(rel("r1", description = "Text"), rel("r2", description = "text ")) shouldHaveSize 1
        }
        "genuinely different content gives no finding" {
            near(rel("r1", label = "uses"), rel("r2", label = "reads")).shouldBeEmpty()
            near(rel("r1", technology = "HTTPS"), rel("r2", technology = "REST")).shouldBeEmpty()
            near(rel("r1", technology = "HTTPS"), rel("r2", technology = null)).shouldBeEmpty()
        }
        "the reverse direction or another bidirectional flag gives no finding" {
            near(rel("r1", label = "Uses"), rel("r2", label = "uses", source = "b", target = "a")).shouldBeEmpty()
            near(rel("r1", label = "Uses"), rel("r2", label = "uses", bidirectional = true)).shouldBeEmpty()
        }
        "exact duplicates are not near-duplicates and stay W001" {
            val m = model(rel("r1"), rel("r2"))
            C4NearDuplicateRelationships.findNear(m).shouldBeEmpty()
            C4DuplicateRelationships.find(m) shouldHaveSize 1
        }
        "a mixed group is W001 for the exact pair and near for the whole group" {
            val m = model(rel("r1", label = "Uses"), rel("r2", label = "Uses"), rel("r3", label = "uses "))
            C4DuplicateRelationships.find(m).single().count shouldBe 2
            val n = C4NearDuplicateRelationships.findNear(m).single()
            n.count shouldBe 3
            n.relationshipIds shouldBe listOf("r1", "r2", "r3")
        }
        "empty and blank labels" {
            near(rel("r1", label = ""), rel("r2", label = "  ")) shouldHaveSize 1
            near(rel("r1", label = ""), rel("r2", label = "x")).shouldBeEmpty()
            near().shouldBeEmpty()
        }
        "two near groups come in order of first occurrence" {
            val f = near(rel("r1", label = "X"), rel("r2", label = "Y"), rel("r3", label = "y "), rel("r4", label = "x"))
            f.map { it.relationshipIds } shouldBe listOf(listOf("r1", "r4"), listOf("r2", "r3"))
        }
        "the message names endpoints, technology, count and ids but not the description or raw whitespace" {
            val m =
                near(
                    rel("r1", label = "Reads\n  data", technology = "HTTPS", description = "SECRET"),
                    rel("r2", label = "reads data", technology = "https", description = "secret"),
                ).single().message()
            m shouldContain "a -> b"
            m shouldContain "near-duplicate"
            m shouldContain "2 occurrences: r1, r2"
            m shouldContain "\"Reads data\""
            m shouldContain "[HTTPS]"
            m shouldNotContain "SECRET"
            m shouldNotContain "secret"
            m shouldNotContain "\n"
        }
    })
