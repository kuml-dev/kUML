package dev.kuml.c4.constraint

import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Relationship
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class C4DuplicateRelationshipsTest :
    StringSpec({
        fun rel(
            id: String,
            source: String = "a",
            target: String = "b",
            label: String = "uses",
            technology: String? = null,
            description: String? = null,
        ) = C4Relationship(id = id, source = source, target = target, label = label, technology = technology, description = description)

        fun model(vararg rels: C4Relationship) = C4Model(id = "m", name = "M", relationships = rels.toList())

        "identical relationships give one finding with count 2 and both ids" {
            val f = C4DuplicateRelationships.find(model(rel("r1", technology = "HTTPS"), rel("r2", technology = "HTTPS")))
            f shouldHaveSize 1
            f.single().count shouldBe 2
            f.single().relationshipIds shouldBe listOf("r1", "r2")
        }
        "three identical relationships give count 3" {
            C4DuplicateRelationships.find(model(rel("r1"), rel("r2"), rel("r3"))).single().count shouldBe 3
        }
        "same pair with a different label does not warn" {
            C4DuplicateRelationships.find(model(rel("r1"), rel("r2", label = "reads"))).shouldBeEmpty()
        }
        "same pair and label with a different technology does not warn" {
            C4DuplicateRelationships.find(model(rel("r1", technology = "HTTPS"), rel("r2", technology = "REST"))).shouldBeEmpty()
        }
        "null versus non-null technology does not warn" {
            C4DuplicateRelationships.find(model(rel("r1"), rel("r2", technology = "HTTPS"))).shouldBeEmpty()
        }
        "the reverse direction does not warn" {
            C4DuplicateRelationships.find(model(rel("r1"), rel("r2", source = "b", target = "a"))).shouldBeEmpty()
        }
        "a different description alone does not warn" {
            C4DuplicateRelationships.find(model(rel("r1", description = "x"), rel("r2", description = "y"))).shouldBeEmpty()
        }
        "no relationships give no finding" {
            C4DuplicateRelationships.find(model()).shouldBeEmpty()
        }
        "two groups come in order of first occurrence" {
            val f =
                C4DuplicateRelationships.find(
                    model(rel("r1", label = "x"), rel("r2", label = "y"), rel("r3", label = "y"), rel("r4", label = "x")),
                )
            f.map { it.label } shouldBe listOf("x", "y")
        }
        "the message names endpoints, technology and count but not the description" {
            val m =
                C4DuplicateRelationships
                    .find(
                        model(
                            rel("r1", technology = "HTTPS", description = "SECRET"),
                            rel("r2", technology = "HTTPS", description = "SECRET"),
                        ),
                    ).single()
                    .message()
            m shouldContain "a -> b"
            m shouldContain "[HTTPS]"
            m shouldContain "2 occurrences"
            m shouldNotContain "SECRET"
        }
    })
