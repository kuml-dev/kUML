package dev.kuml.core.ocl

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile
import dev.kuml.profile.toTagValue
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInterface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class StereotypeValidatorTest :
    FunSpec({

        // ── Test helpers ──────────────────────────────────────────────────────────

        val testNamespace = "dev.kuml.test.profiles.stereotype-validator-test"

        val entityProfile =
            profile("TestJavaEE") {
                namespace = testNamespace
                description = "Test profile"
                version = "1.0.0"

                stereotype("Entity") {
                    extends(UmlMetaclass.Class)
                    property<String>("tableName") // required — no default
                    property<String>("schema") { default = "public" }
                }

                stereotype("Service") {
                    extends(UmlMetaclass.Class)
                    property<Boolean>("transactional") { default = true }
                    constraint("has-ops") {
                        ocl("self.operations->notEmpty()")
                    }
                }
            }

        beforeEach {
            ProfileRegistry.clear()
            ProfileRegistry.register(entityProfile)
        }

        afterEach {
            ProfileRegistry.clear()
        }

        // ── Test 1: missing required property ─────────────────────────────────────

        test("missing required property reports clear violation") {
            val cls =
                UmlClass(
                    id = "User",
                    name = "User",
                    appliedStereotypes =
                        listOf(
                            KumlStereotypeApplication(
                                profileNamespace = testNamespace,
                                stereotypeName = "Entity",
                                tags = emptyMap(), // missing tableName
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            val result = StereotypeValidator.validate(diagram)

            result.valid shouldBe false
            result.violations shouldHaveSize 1
            result.violations[0].message shouldContain "tableName"
            result.violations[0].message shouldContain "Entity"
            result.violations[0].message shouldContain "User"
        }

        // ── Test 2: required property with default does not violate ───────────────

        test("required property with default does not violate") {
            val cls =
                UmlClass(
                    id = "UserService",
                    name = "UserService",
                    appliedStereotypes =
                        listOf(
                            KumlStereotypeApplication(
                                profileNamespace = testNamespace,
                                stereotypeName = "Service",
                                tags = emptyMap(), // transactional has default = true
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            // Service has no required properties without defaults — should not fail on prop check
            // (It may fail on OCL constraint "has-ops" but that's separate)
            val result = StereotypeValidator.validate(diagram)
            // The OCL constraint "has-ops" will fire since operations is empty
            // but the required-property check should not add violations
            val propViolations =
                result.violations.filter { it.constraintName.contains("required-property") }
            propViolations shouldHaveSize 0
        }

        // ── Test 3: extra/unknown tag is ignored in V1.1 ──────────────────────────

        test("extra or unknown tag is ignored in V1.1 (no violation at runtime)") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    appliedStereotypes =
                        listOf(
                            KumlStereotypeApplication(
                                profileNamespace = testNamespace,
                                stereotypeName = "Entity",
                                tags =
                                    mapOf(
                                        "tableName" to "orders".toTagValue(),
                                        "unknownTag" to "ignored".toTagValue(), // extra tag
                                    ),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            val result = StereotypeValidator.validate(diagram)
            // Extra tags are not a violation in V1.1 runtime validation
            result.valid shouldBe true
            result.violations shouldHaveSize 0
        }

        // ── Test 4: targetMetaclass mismatch is reported defensively ──────────────

        test("targetMetaclass mismatch is reported defensively") {
            // Apply Entity (targets Class) to an Interface element
            val iface =
                UmlInterface(
                    id = "IOrder",
                    name = "IOrder",
                    appliedStereotypes =
                        listOf(
                            KumlStereotypeApplication(
                                profileNamespace = testNamespace,
                                stereotypeName = "Entity", // targets Class, not Interface
                                tags = mapOf("tableName" to "orders".toTagValue()),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(iface))
            val result = StereotypeValidator.validate(diagram)

            result.valid shouldBe false
            result.violations shouldHaveSize 1
            result.violations[0].constraintName shouldContain "metaclass"
            result.violations[0].message shouldContain "Interface"
            result.violations[0].message shouldContain "Entity"
        }

        // ── Test 5: stereotype OCL constraint violation propagates ─────────────────

        test("stereotype OCL constraint violation propagates from OclValidator") {
            val cls =
                UmlClass(
                    id = "UserService",
                    name = "UserService",
                    operations = emptyList(), // violates "has-ops": operations->notEmpty()
                    appliedStereotypes =
                        listOf(
                            KumlStereotypeApplication(
                                profileNamespace = testNamespace,
                                stereotypeName = "Service",
                                tags = emptyMap(),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            val result = StereotypeValidator.validate(diagram)

            result.valid shouldBe false
            val oclViolations = result.violations.filter { "has-ops" in it.constraintName }
            oclViolations shouldHaveSize 1
            oclViolations[0].message shouldContain "Service"
        }

        // ── Test 6: clean diagram with all required tags passes ───────────────────

        test("clean diagram with all required tags passes") {
            val cls =
                UmlClass(
                    id = "Product",
                    name = "Product",
                    appliedStereotypes =
                        listOf(
                            KumlStereotypeApplication(
                                profileNamespace = testNamespace,
                                stereotypeName = "Entity",
                                tags = mapOf("tableName" to "products".toTagValue()),
                            ),
                        ),
                )
            val diagram = KumlDiagram(name = "Test", type = DiagramType.CLASS, elements = listOf(cls))
            val result = StereotypeValidator.validate(diagram)

            result.valid shouldBe true
            result.violations shouldHaveSize 0
        }
    })
