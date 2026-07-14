package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for `«Index»` — a repeatable class-level stereotype that synthesizes a composite
 * (multi-column) [dev.kuml.erm.model.ErmIndex], closing the gap where `«Column».unique` only
 * covers single-column uniqueness. Found during the Lapis Cloud MDA production swap: 8 composite
 * UNIQUE constraints and 32 standalone performance indexes in the hand-written Flyway migrations
 * had no way to be expressed from a UML class diagram.
 */
class UmlToErmIndexTest :
    FunSpec({

        val transformer = UmlToErmTransformer()

        test("«Index» synthesizes a composite, named, non-unique ErmIndex") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("Tagesordnungspunkt") {
                        stereotype("Index") {
                            "columns" to listOf("sitzung_id", "position")
                            "name" to "uq_tagesordnungspunkt_position"
                            "unique" to true
                        }
                        attribute("id", "UUID")
                        attribute("sitzungId", "UUID") {
                            stereotype("Column") { "columnName" to "sitzung_id" }
                        }
                        attribute("position", "Int") {
                            stereotype("Column") { "columnName" to "position" }
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val entity = result.output.entities.first { it.name == "tagesordnungspunkts" }
            val index = entity.indexes.single()
            index.name shouldBe "uq_tagesordnungspunkt_position"
            index.unique shouldBe true
            index.attributeIds shouldBe
                listOf(
                    entity.attributeByName("sitzung_id")!!.id,
                    entity.attributeByName("position")!!.id,
                )
        }

        test("«Index» is repeatable — multiple applications on one class each become an ErmIndex") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("WahlStimmzettel") {
                        stereotype("Index") {
                            "columns" to listOf("wahl_id")
                            "name" to "idx_wahl_stimmzettel_wahl"
                        }
                        stereotype("Index") {
                            "columns" to listOf("wahl_id", "member_id")
                            "name" to "uq_wahl_stimmzettel_member"
                            "unique" to true
                        }
                        attribute("id", "UUID")
                        attribute("wahlId", "UUID") {
                            stereotype("Column") { "columnName" to "wahl_id" }
                        }
                        attribute("memberId", "UUID") {
                            stereotype("Column") { "columnName" to "member_id" }
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val entity = result.output.entities.first { it.name == "wahl_stimmzettels" }
            entity.indexes.size shouldBe 2
            entity.indexes.map { it.name } shouldBe listOf("idx_wahl_stimmzettel_wahl", "uq_wahl_stimmzettel_member")
            entity.indexes[0].unique shouldBe false
            entity.indexes[1].unique shouldBe true
        }

        test("«Index» without an explicit name leaves ErmIndex.name null (emitter derives a default)") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("Contribution") {
                        stereotype("Index") { "columns" to listOf("member_id") }
                        attribute("id", "UUID")
                        attribute("memberId", "UUID") {
                            stereotype("Column") { "columnName" to "member_id" }
                        }
                    }
                }
            val result = transformer.transform(diagram, TransformContext()) as TransformResult.Success
            val entity = result.output.entities.first { it.name == "contributions" }
            entity.indexes.single().name shouldBe null
        }

        test("unresolvable «Index» column fails the transform") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("Contribution") {
                        stereotype("Index") { "columns" to listOf("no_such_column") }
                        attribute("id", "UUID")
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            (result is TransformResult.Failure) shouldBe true
        }

        test("empty «Index».columns fails the transform") {
            val diagram =
                classDiagram("D") {
                    applyProfile(ermMappingProfile)
                    classOf("Contribution") {
                        stereotype("Index") { "columns" to emptyList<String>() }
                        attribute("id", "UUID")
                    }
                }
            val result = transformer.transform(diagram, TransformContext())
            (result is TransformResult.Failure) shouldBe true
        }
    })
