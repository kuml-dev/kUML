package dev.kuml.uml.dsl.profile

import dev.kuml.core.dsl.diagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.ProfileRegistry
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlClass
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.interfaceOf
import dev.kuml.uml.dsl.stereotype
import dev.kuml.uml.dsl.umlModel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Convenience accessor matching the pattern used in other DSL tests. */
private val KumlModel.elements
    get() = (root as KumlDiagram).elements

class ProfileDslTest :
    FunSpec(body = {

        beforeEach {
            ProfileRegistry.clear()
        }

        // ── 1. applyProfile + stereotype on classOf stores application ─────────────

        test("applyProfile + stereotype on classOf stores application") {
            val model =
                umlModel(name = "M") {
                    applyProfile(javaEeTestProfile)
                    classOf(name = "User") {
                        stereotype(name = "Entity") {
                            "tableName" to "users"
                        }
                    }
                }

            val cls = model.elements.filterIsInstance<UmlClass>().first()
            cls.appliedStereotypes.size shouldBe 1

            val app = cls.appliedStereotypes.first() as KumlStereotypeApplication
            app.profileNamespace shouldBe "dev.kuml.test.profiles.javaee"
            app.stereotypeName shouldBe "Entity"
        }

        // ── 2. stereotype with infix tag pairs builds correct tags map ────────────

        test("stereotype with infix tag pairs builds correct tags map") {
            val model =
                umlModel(name = "M") {
                    applyProfile(javaEeTestProfile)
                    classOf(name = "User") {
                        stereotype(name = "Entity") {
                            "tableName" to "users"
                            "schema" to "auth"
                        }
                    }
                }

            val app =
                model.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .appliedStereotypes
                    .first() as KumlStereotypeApplication

            app.tags.size shouldBe 2
            app.tags["tableName"] shouldBe TagValue.StringVal("users")
            app.tags["schema"] shouldBe TagValue.StringVal("auth")
        }

        // ── 3. unknown stereotype name reports Levenshtein suggestion ─────────────

        test("unknown stereotype name reports profile suggestion") {
            val ex =
                shouldThrow<IllegalStateException> {
                    umlModel(name = "M") {
                        applyProfile(javaEeTestProfile)
                        classOf(name = "User") {
                            stereotype(name = "Entiy") // typo — should suggest "Entity"
                        }
                    }
                }
            ex.message shouldContain "Entity"
        }

        // ── 4. stereotype on wrong metaclass throws with descriptive message ───────

        test("stereotype on wrong metaclass throws with descriptive message") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    umlModel(name = "M") {
                        applyProfile(javaEeTestProfile)
                        interfaceOf(name = "IUser") {
                            stereotype(name = "Entity") {
                                "tableName" to "i_user"
                            }
                        }
                    }
                }
            ex.message shouldContain "Entity"
            ex.message shouldContain "Class"
            ex.message shouldContain "Interface"
        }

        // ── 5. mandatory property without value throws ─────────────────────────────

        test("mandatory property without value throws") {
            val ex =
                shouldThrow<IllegalStateException> {
                    umlModel(name = "M") {
                        applyProfile(javaEeTestProfile)
                        classOf(name = "User") {
                            // Entity requires "tableName" — omitted intentionally
                            stereotype(name = "Entity") {}
                        }
                    }
                }
            ex.message shouldContain "tableName"
            ex.message shouldContain "Entity"
        }

        // ── 6. multiple stereotypes on same element are preserved in order ─────────

        test("multiple stereotypes on same element are preserved in order") {
            val model =
                umlModel(name = "M") {
                    applyProfile(javaEeTestProfile)
                    classOf(name = "UserSvc") {
                        stereotype(name = "Entity") { "tableName" to "users" }
                        stereotype(name = "Service") {}
                    }
                }

            val apps =
                model.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .appliedStereotypes

            apps.size shouldBe 2
            apps[0].stereotypeName shouldBe "Entity"
            apps[1].stereotypeName shouldBe "Service"
        }

        // ── 7. qualified form spring:Service resolves correctly ───────────────────

        test("qualified form spring:Service resolves correctly") {
            val model =
                umlModel(name = "M") {
                    applyProfile(javaEeTestProfile)
                    applyProfile(springTestProfile)
                    classOf(name = "OrderSvc") {
                        stereotype(name = "spring:Service") {} // qualified — no ambiguity
                    }
                }

            val app =
                model.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .appliedStereotypes
                    .first() as KumlStereotypeApplication

            app.profileNamespace shouldBe "dev.kuml.test.profiles.spring"
            app.stereotypeName shouldBe "Service"
        }

        // ── 8. ambiguous stereotype name throws with qualified suggestion ──────────

        test("ambiguous stereotype name throws with qualified suggestion") {
            val ex =
                shouldThrow<IllegalStateException> {
                    umlModel(name = "M") {
                        applyProfile(javaEeTestProfile)
                        applyProfile(springTestProfile)
                        classOf(name = "OrderSvc") {
                            stereotype(name = "Service") {} // ambiguous!
                        }
                    }
                }
            ex.message shouldContain "multiple applied profiles"
            ex.message shouldContain "Service"
        }

        // ── 9. applyProfile by namespace reads from registry ──────────────────────

        test("applyProfile by namespace reads from registry") {
            ProfileRegistry.register(javaEeTestProfile)

            val model =
                umlModel(name = "M") {
                    applyProfile("dev.kuml.test.profiles.javaee")
                    classOf(name = "User") {
                        stereotype(name = "Entity") { "tableName" to "users" }
                    }
                }

            val app =
                model.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .appliedStereotypes
                    .first() as KumlStereotypeApplication

            app.profileNamespace shouldBe "dev.kuml.test.profiles.javaee"
        }

        // ── 10. applyProfile by unknown namespace throws ───────────────────────────

        test("applyProfile by unknown namespace throws") {
            val ex =
                shouldThrow<IllegalStateException> {
                    umlModel(name = "M") {
                        applyProfile("dev.kuml.nonexistent.profile")
                        classOf(name = "User") {}
                    }
                }
            ex.message shouldContain "dev.kuml.nonexistent.profile"
            ex.message shouldContain "Registered:"
        }

        // ── 11. applyProfile works on umlModel as well (D3) ──────────────────────

        test("applyProfile works on umlModel as well (D3)") {
            val model =
                umlModel(name = "M") {
                    applyProfile(javaEeTestProfile)
                    classOf(name = "Repository") {
                        stereotype(name = "Service") {}
                    }
                }

            val app =
                model.elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .appliedStereotypes
                    .first() as KumlStereotypeApplication

            app.stereotypeName shouldBe "Service"
            app.profileNamespace shouldBe "dev.kuml.test.profiles.javaee"
        }

        // ── 12. applyProfile works on diagram { } scope ───────────────────────────

        test("applyProfile works on diagram scope") {
            val diag =
                diagram(name = "D", type = DiagramType.CLASS) {
                    applyProfile(javaEeTestProfile)
                    classOf(name = "User") {
                        stereotype(name = "Entity") { "tableName" to "users" }
                    }
                }

            val cls = diag.elements.filterIsInstance<UmlClass>().first()
            cls.appliedStereotypes.size shouldBe 1
        }

        // ── 13. stereotype with no applied profiles throws clear error ────────────

        test("stereotype with no applied profiles throws clear error") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    umlModel(name = "M") {
                        // NO applyProfile call
                        classOf(name = "User") {
                            stereotype(name = "Entity") {}
                        }
                    }
                }
            ex.message shouldContain "no applyProfile"
        }
    })
