package dev.kuml.uml.dsl.profile

import dev.kuml.core.dsl.stateDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.state
import dev.kuml.uml.dsl.stereotype
import dev.kuml.uml.dsl.transition
import dev.kuml.uml.dsl.umlModel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Convenience accessor matching the pattern used in other DSL tests. */
private val KumlModel.elements
    get() = (root as KumlDiagram).elements

// ── Test profiles ─────────────────────────────────────────────────────────────

private val featureTestProfile: KumlProfile =
    profile("Feature") {
        namespace = "dev.kuml.test.profiles.feature"
        stereotype("Scheduled") {
            extends(UmlMetaclass.Operation)
            property<String>("cron") { default = "* * * * *" }
        }
        stereotype("RequestParam") {
            extends(UmlMetaclass.Parameter)
            property<Boolean>("required") { default = true }
        }
        stereotype("PersistenceContext") {
            extends(UmlMetaclass.Property)
        }
        stereotype("InitialState") {
            extends(UmlMetaclass.State)
        }
        stereotype("Guard") {
            extends(UmlMetaclass.Transition)
        }
    }

private val featureTestProfile2: KumlProfile =
    profile("Feature2") {
        namespace = "dev.kuml.test.profiles.feature2"
        stereotype("Scheduled") {
            extends(UmlMetaclass.Operation)
            property<String>("expression") { default = "" }
        }
    }

// ── Tests ─────────────────────────────────────────────────────────────────────

class FeatureStereotypeDslTest :
    FunSpec(body = {

        beforeEach {
            ProfileRegistry.clear()
        }

        // ── Operation (3 tests) ────────────────────────────────────────────────

        test("operation stereotype — simple name stores appliedStereotype entry") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    classOf("Scheduler") {
                        operation("tick") {
                            stereotype("Scheduled")
                        }
                    }
                }

            val op =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .operations
                    .first()

            op.appliedStereotypes shouldHaveSize 1
            op.appliedStereotypes.first().stereotypeName shouldBe "Scheduled"
        }

        test("operation stereotype — tagged value stored correctly") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    classOf("Scheduler") {
                        operation("tick") {
                            stereotype("Scheduled") {
                                "cron" to "0 0 * * *"
                            }
                        }
                    }
                }

            val app =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .operations
                    .first()
                    .appliedStereotypes
                    .first() as KumlStereotypeApplication

            app.tags["cron"] shouldBe TagValue.StringVal("0 0 * * *")
            app.profileNamespace shouldBe "dev.kuml.test.profiles.feature"
        }

        test("operation backward-compat — flat call without block produces empty appliedStereotypes") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    classOf("Scheduler") {
                        operation("tick")
                    }
                }

            val op =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .operations
                    .first()

            op.appliedStereotypes shouldHaveSize 0
        }

        // ── Property / Attribute (3 tests) ────────────────────────────────────

        test("attribute block form stores appliedStereotype on UmlProperty") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    classOf("UserRepository") {
                        attribute("connection", "DataSource") {
                            stereotype("PersistenceContext")
                        }
                    }
                }

            val prop =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .attributes
                    .first()

            prop.appliedStereotypes shouldHaveSize 1
            prop.appliedStereotypes.first().stereotypeName shouldBe "PersistenceContext"
        }

        test("attribute block form with classifier handle stores appliedStereotype") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    val dataSource = classOf("DataSource")
                    classOf("UserRepository") {
                        attribute("connection", dataSource) {
                            stereotype("PersistenceContext")
                        }
                    }
                }

            val prop =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .last()
                    .attributes
                    .first()

            prop.appliedStereotypes shouldHaveSize 1
            prop.appliedStereotypes.first().stereotypeName shouldBe "PersistenceContext"
        }

        test("attribute backward-compat — flat call without block produces empty appliedStereotypes") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    classOf("User") {
                        attribute("name", "String")
                    }
                }

            val prop =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .attributes
                    .first()

            prop.appliedStereotypes shouldHaveSize 0
        }

        // ── Parameter (3 tests) ───────────────────────────────────────────────

        test("parameter block form stores appliedStereotype on UmlParameter") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    classOf("UserService") {
                        operation("findByEmail") {
                            parameter("email", "String") {
                                stereotype("RequestParam")
                            }
                        }
                    }
                }

            val param =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .operations
                    .first()
                    .parameters
                    .first()

            param.appliedStereotypes shouldHaveSize 1
            param.appliedStereotypes.first().stereotypeName shouldBe "RequestParam"
        }

        test("multiple parameters with different stereotypes stored independently") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    classOf("UserService") {
                        operation("register") {
                            parameter("email", "String") {
                                stereotype("RequestParam")
                            }
                            parameter("name", "String")
                        }
                    }
                }

            val params =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .operations
                    .first()
                    .parameters

            params shouldHaveSize 2
            params[0].appliedStereotypes shouldHaveSize 1
            params[0].appliedStereotypes.first().stereotypeName shouldBe "RequestParam"
            params[1].appliedStereotypes shouldHaveSize 0
        }

        test("parameter backward-compat — flat call without block produces empty appliedStereotypes") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    classOf("UserService") {
                        operation("findById") {
                            parameter("id", "Long")
                        }
                    }
                }

            val param =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .operations
                    .first()
                    .parameters
                    .first()

            param.appliedStereotypes shouldHaveSize 0
        }

        // ── Ambiguity resolution (1 test) ─────────────────────────────────────

        test("ambiguous operation stereotype resolved via qualified form") {
            val model =
                umlModel("M") {
                    applyProfile(featureTestProfile)
                    applyProfile(featureTestProfile2)
                    classOf("Scheduler") {
                        operation("tick") {
                            stereotype("dev.kuml.test.profiles.feature:Scheduled") {
                                "cron" to "0 0 * * *"
                            }
                        }
                    }
                }

            val app =
                model
                    .elements
                    .filterIsInstance<UmlClass>()
                    .first()
                    .operations
                    .first()
                    .appliedStereotypes
                    .first() as KumlStereotypeApplication

            app.profileNamespace shouldBe "dev.kuml.test.profiles.feature"
            app.stereotypeName shouldBe "Scheduled"
        }

        // ── Element metaclass mismatch (1 test) ───────────────────────────────

        test("attribute stereotype for Operation metaclass throws mismatch error") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    umlModel("M") {
                        applyProfile(featureTestProfile)
                        classOf("Scheduler") {
                            attribute("x", "Int") {
                                stereotype("Scheduled") // Scheduled targets Operation, not Property
                            }
                        }
                    }
                }
            ex.message shouldContain "Scheduled"
            ex.message shouldContain "Operation"
            ex.message shouldContain "Property"
        }

        // ── State machine (2 tests — AP-1.4 implemented) ─────────────────────

        test("state stereotype stores appliedStereotype on UmlState") {
            val diag =
                stateDiagram("Lifecycle") {
                    applyProfile(featureTestProfile)
                    state("Idle") {
                        stereotype("InitialState")
                    }
                }

            val sm = diag.elements.first() as UmlStateMachine
            val state = sm.vertices.filterIsInstance<UmlState>().first()

            state.appliedStereotypes shouldHaveSize 1
            state.appliedStereotypes.first().stereotypeName shouldBe "InitialState"
        }

        test("transition stereotype stores appliedStereotype on UmlTransition") {
            val diag =
                stateDiagram("Lifecycle") {
                    applyProfile(featureTestProfile)
                    val idle = state("Idle")
                    val active = state("Active")
                    transition(idle, active) {
                        trigger = "start()"
                        stereotype("Guard")
                    }
                }

            val sm = diag.elements.first() as UmlStateMachine
            val t = sm.transitions.filterIsInstance<UmlTransition>().first()

            t.appliedStereotypes shouldHaveSize 1
            t.appliedStereotypes.first().stereotypeName shouldBe "Guard"
        }
    })
