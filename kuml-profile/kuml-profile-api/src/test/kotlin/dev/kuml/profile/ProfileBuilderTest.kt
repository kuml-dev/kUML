package dev.kuml.profile

import dev.kuml.profile.builder.profile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ProfileBuilderTest :
    StringSpec({

        "profile() builds with correct name and namespace" {
            val p =
                profile("MyProfile") {
                    namespace = "dev.example.test"
                }
            p.name shouldBe "MyProfile"
            p.namespace shouldBe "dev.example.test"
        }

        "stereotype with extends and properties is built correctly" {
            val p =
                profile("EntityProfile") {
                    namespace = "dev.example.entity"
                    stereotype("Entity") {
                        extends(UmlMetaclass.Class)
                        property<String>("tableName")
                        property<Boolean>("cacheable") { default = false }
                    }
                }
            val s = p.stereotype("Entity")!!
            s.targetMetaclass shouldBe UmlMetaclass.Class
            s.properties shouldHaveSize 2
            s.properties[0].name shouldBe "tableName"
            s.properties[1].name shouldBe "cacheable"
        }

        "stereotype without extends throws clear error" {
            val ex =
                shouldThrow<IllegalStateException> {
                    profile("Bad") {
                        namespace = "dev.example.bad"
                        stereotype("NoTarget") {
                            // no extends(...)
                        }
                    }
                }
            ex.message shouldContain "must declare extends(UmlMetaclass.X)"
        }

        "profile without namespace throws" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    profile("NoNs") {
                        // namespace intentionally not set
                    }
                }
            ex.message shouldContain "namespace must be set"
        }

        "duplicate stereotype names throw" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    profile("Dup") {
                        namespace = "dev.example.dup"
                        stereotype("Entity") { extends(UmlMetaclass.Class) }
                        stereotype("Entity") { extends(UmlMetaclass.Interface) }
                    }
                }
            ex.message shouldContain "duplicate stereotype names"
        }

        "profile extends another profile by object reference" {
            val base =
                profile("Base") {
                    namespace = "dev.example.base"
                    stereotype("Base") { extends(UmlMetaclass.Class) }
                }
            val child =
                profile("Child") {
                    namespace = "dev.example.child"
                    extends(base)
                }
            child.extendsProfiles shouldBe listOf("dev.example.base")
        }

        "profile extends another profile by namespace string" {
            val child =
                profile("Child") {
                    namespace = "dev.example.child2"
                    extends("dev.example.base")
                }
            child.extendsProfiles shouldBe listOf("dev.example.base")
        }

        "specializes without matching parent in same profile and no extends throws" {
            val ex =
                shouldThrow<IllegalStateException> {
                    profile("Spec") {
                        namespace = "dev.example.spec"
                        stereotype("Child") {
                            extends(UmlMetaclass.Class)
                            specializes = "NonExistentParent"
                        }
                    }
                }
            ex.message shouldContain "specializes"
        }

        "property with disallowed type throws" {
            data class AnyClass(
                val x: Int,
            )
            val ex =
                shouldThrow<IllegalArgumentException> {
                    profile("Bad") {
                        namespace = "dev.example.bad2"
                        stereotype("Entity") {
                            extends(UmlMetaclass.Class)
                            property<AnyClass>("illegal")
                        }
                    }
                }
            ex.message shouldContain "not allowed in V1.1"
        }

        "stereotype() returns null for unknown name" {
            val p =
                profile("P") {
                    namespace = "dev.example.p"
                    stereotype("A") { extends(UmlMetaclass.Class) }
                }
            p.stereotype("Unknown") shouldBe null
        }

        // ── V1.1 AP-3.5.1: UmlMetaclass.Collaboration smoke-test ─────────────────

        "UmlMetaclass.Collaboration is a valid extends target for a profile stereotype" {
            val p =
                profile("SoaML") {
                    namespace = "dev.soaml.test"
                    stereotype("ServiceContract") {
                        extends(UmlMetaclass.Collaboration)
                    }
                }
            val s = p.stereotype("ServiceContract")!!
            s.targetMetaclass shouldBe UmlMetaclass.Collaboration
            s.name shouldBe "ServiceContract"
        }

        "profile with multiple stereotypes on different metaclasses" {
            val p =
                profile("Multi") {
                    namespace = "dev.example.multi"
                    stereotype("OnClass") { extends(UmlMetaclass.Class) }
                    stereotype("OnInterface") { extends(UmlMetaclass.Interface) }
                    stereotype("OnOperation") { extends(UmlMetaclass.Operation) }
                }
            p.stereotypes shouldHaveSize 3
            p.stereotypes.map { it.targetMetaclass } shouldContainExactlyInAnyOrder
                listOf(
                    UmlMetaclass.Class,
                    UmlMetaclass.Interface,
                    UmlMetaclass.Operation,
                )
        }
    })
