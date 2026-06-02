package dev.kuml.codegen.kotlin

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class KotlinCodeGeneratorTest :
    FunSpec(body = {

        test("generates data class from UmlClass with attributes") {
            val diagram =
                KumlDiagram(
                    name = "TestDiagram",
                    elements =
                        listOf(
                            UmlClass(
                                id = "order-id",
                                name = "Order",
                                attributes =
                                    listOf(
                                        UmlProperty(
                                            id = "prop-id",
                                            name = "id",
                                            type = UmlTypeRef("UUID"),
                                            multiplicity = Multiplicity(1, 1),
                                        ),
                                        UmlProperty(
                                            id = "prop-amount",
                                            name = "amount",
                                            type = UmlTypeRef("Double"),
                                            multiplicity = Multiplicity(1, 1),
                                        ),
                                    ),
                            ),
                        ),
                )

            val tmpDir = Files.createTempDirectory("kuml-gen-test").toFile()
            try {
                val files = KotlinCodeGenerator().generate(diagram, tmpDir, emptyMap())
                val content = files.single().readText()
                content shouldContain "data class Order"
                content shouldContain "val id: java.util.UUID"
                content shouldContain "val amount: Double"
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        test("generates interface from UmlInterface") {
            val diagram =
                KumlDiagram(
                    name = "TestDiagram",
                    elements =
                        listOf(
                            UmlInterface(
                                id = "repo-id",
                                name = "OrderRepository",
                                operations =
                                    listOf(
                                        UmlOperation(
                                            id = "op-id",
                                            name = "findById",
                                            returnType = UmlTypeRef("Order"),
                                        ),
                                    ),
                            ),
                        ),
                )

            val tmpDir = Files.createTempDirectory("kuml-gen-test").toFile()
            try {
                val files = KotlinCodeGenerator().generate(diagram, tmpDir, emptyMap())
                val content = files.single().readText()
                content shouldContain "interface OrderRepository"
                content shouldContain "fun findById"
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        test("generates enum class from UmlEnumeration") {
            val diagram =
                KumlDiagram(
                    name = "TestDiagram",
                    elements =
                        listOf(
                            UmlEnumeration(
                                id = "status-id",
                                name = "OrderStatus",
                                literals =
                                    listOf(
                                        UmlEnumerationLiteral(id = "lit-draft", name = "DRAFT"),
                                        UmlEnumerationLiteral(id = "lit-confirmed", name = "CONFIRMED"),
                                    ),
                            ),
                        ),
                )

            val tmpDir = Files.createTempDirectory("kuml-gen-test").toFile()
            try {
                val files = KotlinCodeGenerator().generate(diagram, tmpDir, emptyMap())
                val content = files.single().readText()
                content shouldContain "enum class OrderStatus"
                content shouldContain "DRAFT"
                content shouldContain "CONFIRMED"
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        test("respects package option") {
            val diagram =
                KumlDiagram(
                    name = "TestDiagram",
                    elements =
                        listOf(
                            UmlClass(
                                id = "product-id",
                                name = "Product",
                                attributes =
                                    listOf(
                                        UmlProperty(
                                            id = "prop-name",
                                            name = "name",
                                            type = UmlTypeRef("String"),
                                            multiplicity = Multiplicity(1, 1),
                                        ),
                                    ),
                            ),
                        ),
                )

            val tmpDir = Files.createTempDirectory("kuml-gen-test").toFile()
            try {
                val files = KotlinCodeGenerator().generate(diagram, tmpDir, mapOf("package" to "com.example"))
                val content = files.single().readText()
                content shouldContain "package com.example"
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    })
