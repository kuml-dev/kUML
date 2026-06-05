package dev.kuml.codegen.java

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

private data class TestStereo(
    override val profileNamespace: String = "test",
    override val stereotypeName: String,
    override val tags: Map<String, TagValue> = emptyMap(),
) : AppliedStereotype

class JavaCodeGeneratorTest :
    FunSpec({

        test("generates POJO class with private fields, no-args + all-args constructor, getters/setters") {
            val cls =
                UmlClass(
                    id = "user-id",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "id",
                                name = "id",
                                type = UmlTypeRef("UUID"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                            UmlProperty(
                                id = "email",
                                name = "email",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val out = Files.createTempDirectory("kuml-gen-java-test").toFile()
            try {
                val files = JavaCodeGenerator().generate(diagram, out, emptyMap())
                files.size shouldBe 1
                val content = files.single().readText()
                content shouldContain "public class User"
                content shouldContain "private java.util.UUID id;"
                content shouldContain "private String email;"
                content shouldContain "public User() {}"
                content shouldContain "public User(java.util.UUID id, String email)"
                content shouldContain "public java.util.UUID getId()"
                content shouldContain "public void setEmail(String email)"
            } finally {
                out.deleteRecursively()
            }
        }

        test("package option writes file into matching directory structure") {
            val cls = UmlClass(id = "x", name = "Order")
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val out = Files.createTempDirectory("kuml-gen-java-test").toFile()
            try {
                val files =
                    JavaCodeGenerator().generate(
                        diagram,
                        out,
                        mapOf("package" to "com.example.domain"),
                    )
                files.size shouldBe 1
                val file = files.single()
                file.path shouldContain "com/example/domain"
                file.name shouldBe "Order.java"
                file.readText() shouldContain "package com.example.domain;"
            } finally {
                out.deleteRecursively()
            }
        }

        test("class with «Entity» stereotype gets @jakarta.persistence.Entity annotation") {
            val cls =
                UmlClass(
                    id = "user-id",
                    name = "User",
                    appliedStereotypes =
                        listOf(
                            TestStereo(
                                stereotypeName = "Entity",
                                tags = mapOf("tableName" to TagValue.StringVal("users")),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val out = Files.createTempDirectory("kuml-gen-java-test").toFile()
            try {
                val content = JavaCodeGenerator().generate(diagram, out, emptyMap()).single().readText()
                content shouldContain "@jakarta.persistence.Entity(name = \"users\")"
                content shouldContain "public class User"
            } finally {
                out.deleteRecursively()
            }
        }

        test("«Transient» property is excluded from output") {
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "id",
                                name = "id",
                                type = UmlTypeRef("Long"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                            UmlProperty(
                                id = "tmp",
                                name = "temporary",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(0, 1),
                                appliedStereotypes = listOf(TestStereo(stereotypeName = "Transient")),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val out = Files.createTempDirectory("kuml-gen-java-test").toFile()
            try {
                val content = JavaCodeGenerator().generate(diagram, out, emptyMap()).single().readText()
                content shouldContain "private long id;"
                content shouldNotContain "temporary"
            } finally {
                out.deleteRecursively()
            }
        }

        test("interface generates as public interface with abstract method signatures") {
            val iface =
                UmlInterface(
                    id = "i",
                    name = "Repository",
                    operations =
                        listOf(
                            UmlOperation(id = "op", name = "findAll", returnType = UmlTypeRef("List")),
                        ),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(iface))
            val out = Files.createTempDirectory("kuml-gen-java-test").toFile()
            try {
                val content = JavaCodeGenerator().generate(diagram, out, emptyMap()).single().readText()
                content shouldContain "public interface Repository"
                content shouldContain "findAll();"
                content shouldNotContain "private "
            } finally {
                out.deleteRecursively()
            }
        }

        test("records mode emits public record with components") {
            val cls =
                UmlClass(
                    id = "p",
                    name = "Point",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "x",
                                name = "x",
                                type = UmlTypeRef("Double"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                            UmlProperty(
                                id = "y",
                                name = "y",
                                type = UmlTypeRef("Double"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val out = Files.createTempDirectory("kuml-gen-java-test").toFile()
            try {
                val content =
                    JavaCodeGenerator().generate(diagram, out, mapOf("java-style" to "records")).single().readText()
                content shouldContain "public record Point(double x, double y) {}"
            } finally {
                out.deleteRecursively()
            }
        }

        test("lombok mode adds @lombok.Data annotation and skips manual getters") {
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "n",
                                name = "name",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val out = Files.createTempDirectory("kuml-gen-java-test").toFile()
            try {
                val content =
                    JavaCodeGenerator().generate(diagram, out, mapOf("java-style" to "lombok")).single().readText()
                content shouldContain "@lombok.Data"
                content shouldContain "@lombok.NoArgsConstructor"
                content shouldContain "@lombok.AllArgsConstructor"
                content shouldContain "private String name;"
                content shouldNotContain "public String getName()"
            } finally {
                out.deleteRecursively()
            }
        }

        test("enum generates public enum with literals") {
            val en =
                UmlEnumeration(
                    id = "s",
                    name = "Status",
                    literals =
                        listOf(
                            UmlEnumerationLiteral(id = "a", name = "Active"),
                            UmlEnumerationLiteral(id = "i", name = "Inactive"),
                        ),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(en))
            val out = Files.createTempDirectory("kuml-gen-java-test").toFile()
            try {
                val content = JavaCodeGenerator().generate(diagram, out, emptyMap()).single().readText()
                content shouldContain "public enum Status"
                content shouldContain "Active"
                content shouldContain "Inactive"
            } finally {
                out.deleteRecursively()
            }
        }

        test("multiplicity (0,*) wraps in java.util.List") {
            val cls =
                UmlClass(
                    id = "u",
                    name = "User",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "r",
                                name = "roles",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(0, null),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "D", elements = listOf(cls))
            val out = Files.createTempDirectory("kuml-gen-java-test").toFile()
            try {
                val content = JavaCodeGenerator().generate(diagram, out, emptyMap()).single().readText()
                content shouldContain "private java.util.List<String> roles;"
            } finally {
                out.deleteRecursively()
            }
        }

        test("provider exposed via ServiceLoader so CodeGenRegistry finds java plugin") {
            val provider = JavaCodeGeneratorProvider()
            provider.generator().id shouldBe "java"
        }
    })
