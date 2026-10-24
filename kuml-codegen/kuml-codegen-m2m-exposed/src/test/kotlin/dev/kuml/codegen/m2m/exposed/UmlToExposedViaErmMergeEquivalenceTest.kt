package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.transform.umlerm.UmlModelMerger
import dev.kuml.transform.umlerm.UmlModelSource
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.enumOf
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Equivalence proof (kUML#10): three diagrams with stubs, an M:N association and a duplicated enum,
 * merged by [UmlModelMerger] and run through `uml-to-exposed-via-erm`, produce byte-identical files
 * to the single diagram that holds the same elements in merged order without stubs.
 */
class UmlToExposedViaErmMergeEquivalenceTest :
    FunSpec({

        fun files(diagram: KumlDiagram): Map<String, String> {
            val result =
                UmlToExposedViaErmScriptTransformer()
                    .transform(source = diagram, ctx = TransformContext(options = mapOf("idType" to "uuid", "package" to "demo.db")))
            return result
                .shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>()
                .output
                .associate { it.relativePath to it.content }
        }

        test("merged three-script model generates exactly the single diagram's files") {
            val a =
                classDiagram(name = "A") {
                    applyProfile(ermMappingProfile)
                    classOf(name = "Member") {
                        stereotype(name = "Entity") {
                            "tableName" to "member"
                            "kotlinObjectName" to "MemberTable"
                        }
                        attribute(name = "id", type = "UUID") { stereotype(name = "Id") }
                        attribute(name = "name", type = "String")
                    }
                    enumOf(name = "MemberStatus") { literal(name = "ACTIVE") }
                }
            val b =
                classDiagram(name = "B") {
                    applyProfile(ermMappingProfile)
                    val member =
                        classOf(name = "Member") {
                            stereotype(name = "Entity") {
                                "tableName" to "member"
                                "kotlinObjectName" to "MemberTable"
                            }
                            attribute(name = "id", type = "UUID") { stereotype(name = "Id") }
                        }
                    val order =
                        classOf(name = "Order") {
                            stereotype(name = "Entity") {
                                "tableName" to "customer_order"
                                "kotlinObjectName" to "OrderTable"
                            }
                            attribute(name = "id", type = "UUID") { stereotype(name = "Id") }
                        }
                    association(source = order, target = member, id = "assoc-order-member") {
                        source { multiplicity("0..*") }
                        target {
                            multiplicity("1")
                            role = "memberId"
                        }
                    }
                }
            val c =
                classDiagram(name = "C") {
                    applyProfile(ermMappingProfile)
                    val order =
                        classOf(name = "Order") {
                            stereotype(name = "Entity") {
                                "tableName" to "customer_order"
                                "kotlinObjectName" to "OrderTable"
                            }
                            attribute(name = "id", type = "UUID") { stereotype(name = "Id") }
                        }
                    val tag =
                        classOf(name = "Tag") {
                            stereotype(name = "Entity") {
                                "tableName" to "tag"
                                "kotlinObjectName" to "TagTable"
                            }
                            attribute(name = "id", type = "UUID") { stereotype(name = "Id") }
                            attribute(name = "label", type = "String")
                        }
                    enumOf(name = "MemberStatus") { literal(name = "ACTIVE") }
                    association(source = order, target = tag, id = "assoc-order-tag") {
                        source { multiplicity("0..*") }
                        target { multiplicity("0..*") }
                    }
                }
            val single =
                classDiagram(name = "Shop") {
                    applyProfile(ermMappingProfile)
                    val member =
                        classOf(name = "Member") {
                            stereotype(name = "Entity") {
                                "tableName" to "member"
                                "kotlinObjectName" to "MemberTable"
                            }
                            attribute(name = "id", type = "UUID") { stereotype(name = "Id") }
                            attribute(name = "name", type = "String")
                        }
                    enumOf(name = "MemberStatus") { literal(name = "ACTIVE") }
                    val order =
                        classOf(name = "Order") {
                            stereotype(name = "Entity") {
                                "tableName" to "customer_order"
                                "kotlinObjectName" to "OrderTable"
                            }
                            attribute(name = "id", type = "UUID") { stereotype(name = "Id") }
                        }
                    val tag =
                        classOf(name = "Tag") {
                            stereotype(name = "Entity") {
                                "tableName" to "tag"
                                "kotlinObjectName" to "TagTable"
                            }
                            attribute(name = "id", type = "UUID") { stereotype(name = "Id") }
                            attribute(name = "label", type = "String")
                        }
                    enumOf(name = "MemberStatus") { literal(name = "ACTIVE") }
                    association(source = order, target = member, id = "assoc-order-member") {
                        source { multiplicity("0..*") }
                        target {
                            multiplicity("1")
                            role = "memberId"
                        }
                    }
                    association(source = order, target = tag, id = "assoc-order-tag") {
                        source { multiplicity("0..*") }
                        target { multiplicity("0..*") }
                    }
                }

            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            UmlModelSource(origin = "30-c", diagram = c),
                            UmlModelSource(origin = "10-a", diagram = a),
                            UmlModelSource(origin = "20-b", diagram = b),
                        ),
                )

            val expected = files(single)
            expected.keys.sorted() shouldBe listOf("MemberTable.kt", "OrderTable.kt", "OrdersTags.kt", "TagTable.kt")
            files(merged) shouldBe expected
        }
    })
