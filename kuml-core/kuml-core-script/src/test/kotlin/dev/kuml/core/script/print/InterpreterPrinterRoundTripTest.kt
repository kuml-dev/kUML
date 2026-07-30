package dev.kuml.core.script.print

import dev.kuml.core.dsl.classDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.EvaluatedScript
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.interpreter.InterpreterScriptEvaluator
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlConstraintKind
import dev.kuml.uml.Visibility
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.comment
import dev.kuml.uml.dsl.constraint
import dev.kuml.uml.dsl.dependency
import dev.kuml.uml.dsl.enumOf
import dev.kuml.uml.dsl.generalization
import dev.kuml.uml.dsl.interfaceOf
import dev.kuml.uml.dsl.literal
import dev.kuml.uml.dsl.operation
import dev.kuml.uml.dsl.print.InterpreterUmlModelDslPrinter
import dev.kuml.uml.dsl.realization
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Full-equality round-trip tests for [InterpreterUmlModelDslPrinter]: build a
 * [KumlDiagram] via the real `classDiagram { … }` DSL builders (never raw
 * data-class construction — see [InterpreterUmlModelDslPrinter]'s KDoc:
 * relationship IDs are always re-derived by [InterpreterScriptEvaluator], so a
 * hand-assigned relationship ID in a raw fixture would spuriously fail a
 * `shouldBe` check for reasons unrelated to the printer's correctness), print
 * it via [InterpreterUmlModelDslPrinter], evaluate the printed script through
 * the **real** [InterpreterScriptEvaluator] (never [dev.kuml.core.script.InProcessScriptEvaluator]),
 * and assert the re-parsed [KumlDiagram] is fully data-class-equal to the
 * original.
 *
 * This directly proves the printer's output is not just syntactically
 * plausible text but actually round-trips through the interpreter it targets.
 *
 * ## Element-ordering discipline
 *
 * Same discipline as [PrinterRoundTripTest]: [InterpreterUmlModelDslPrinter]
 * re-emits `diagram.elements` grouped by kind in a fixed canonical order
 * (enumerations, interfaces, classes, generalizations, realizations,
 * associations, dependencies, comments). Every fixture below declares its DSL
 * calls in that same canonical order at the top level so the re-parsed
 * diagram's flat `elements` list lines up with the original's.
 *
 * ## Scope
 *
 * These tests cover exactly the subset [InterpreterUmlModelDslPrinter]
 * documents as round-tripping. A second group of tests below asserts the
 * printer's *documented limitations* are exactly what they claim to be — not
 * fidelity assertions, but regression guards on the known, honest boundary of
 * what does and does not survive the interpreter dialect.
 */
class InterpreterPrinterRoundTripTest :
    StringSpec({

        fun reparse(printed: String): KumlDiagram {
            val result = InterpreterScriptEvaluator.evaluate(source = printed, fileName = "interpreter-roundtrip.kuml.kts")
            require(result is EvaluatedScript.Success) { "re-parse failed: $result\n---\n$printed" }
            val extracted = result.diagram
            require(extracted is ExtractedDiagram.Uml) { "expected a UML diagram, got: $extracted" }
            return extracted.diagram
        }

        "generalization round-trips via val handles" {
            val original =
                classDiagram(name = "D") {
                    val animal = classOf(name = "Animal") { isAbstract = true }
                    val dog = classOf(name = "Dog")
                    generalization(specific = dog, general = animal)
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            printed shouldContain "generalization(specific ="
            reparse(printed) shouldBe original
        }

        "realization round-trips via val handles" {
            val original =
                classDiagram(name = "D") {
                    val greeter = interfaceOf(name = "Greeter") { operation(name = "greet") }
                    val greeterImpl = classOf(name = "GreeterImpl")
                    realization(implementing = greeterImpl, iface = greeter)
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "dependency (with and without name) round-trips" {
            val original =
                classDiagram(name = "D") {
                    val a = classOf(name = "Order")
                    val b = classOf(name = "NotificationService")
                    val c = classOf(name = "AuditLog")
                    dependency(client = a, supplier = b, name = "notifies")
                    dependency(client = a, supplier = c)
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "association name, aggregation, role and navigable round-trip" {
            val original =
                classDiagram(name = "D") {
                    val customer = classOf(name = "Customer")
                    val order = classOf(name = "Order")
                    association(source = customer, target = order) {
                        name = "places"
                        aggregation = AggregationKind.COMPOSITE
                        source { navigable = false }
                        target {
                            multiplicity("0..*")
                            role = "orders"
                        }
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "circular association (two classes, each end referencing the other) round-trips" {
            val original =
                classDiagram(name = "D") {
                    val husband = classOf(name = "Husband")
                    val wife = classOf(name = "Wife")
                    association(source = husband, target = wife) {
                        name = "marriedTo"
                        source { role = "wife" }
                        target { role = "husband" }
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "self-association round-trips" {
            val original =
                classDiagram(name = "D") {
                    val node = classOf(name = "Node")
                    association(source = node, target = node) {
                        name = "parentOf"
                        target { role = "children" }
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            printed shouldContain "association(source = node, target = node)"
            reparse(printed) shouldBe original
        }

        "operation parameters (default IN direction, no defaultValue) and returns() round-trip in declared order" {
            // NOTE: parameter `direction`/`defaultValue` do NOT round-trip through the
            // interpreter (see the documented-limitation test below) — this fixture
            // deliberately sticks to the default IN direction / no defaultValue so this
            // test asserts real full-equality round-tripping, not a false pass.
            val original =
                classDiagram(name = "D") {
                    classOf(name = "Order") {
                        operation(name = "place") {
                            parameter(name = "items", type = "List<OrderItem>")
                            parameter(name = "flags", type = "Int")
                            returns("OrderId")
                        }
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            printed shouldContain "returns(typeName = \"OrderId\")"
            // ordering: items must appear before flags
            (printed.indexOf("\"items\"") < printed.indexOf("\"flags\"")) shouldBe true
            reparse(printed) shouldBe original
        }

        "non-default visibility on class, operation and attribute round-trips" {
            val original =
                classDiagram(name = "D") {
                    classOf(name = "Order") {
                        visibility = Visibility.PROTECTED
                        attribute(name = "id", type = "UUID", visibility = Visibility.PUBLIC)
                        operation(name = "internalHelper") {
                            visibility = Visibility.PRIVATE
                        }
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "enum-typed attribute (val-handle form) round-trips — proves attribute referencedId resolution" {
            val original =
                classDiagram(name = "D") {
                    val status =
                        enumOf(name = "OrderStatus") {
                            literal(name = "DRAFT")
                            literal(name = "CONFIRMED")
                        }
                    classOf(name = "Order") {
                        attribute(name = "status", type = status)
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            // The val identifier is derived from the enum's name ("OrderStatus"),
            // not the attribute's name ("status") — lowercased first letter: "orderStatus".
            printed shouldContain "attribute(name = \"status\", type = orderStatus)"
            reparse(printed) shouldBe original
        }

        "single-anchor comment round-trips" {
            val original =
                classDiagram(name = "D") {
                    val order = classOf(name = "Order")
                    comment(text = "Encapsulates the order lifecycle.", firstAnchor = order)
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            printed shouldContain "firstAnchor = order"
            reparse(printed) shouldBe original
        }

        "zero-anchor comment round-trips" {
            val original =
                classDiagram(name = "D") {
                    comment(text = "General remark, not attached to anything.")
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "attribute defaultValue and isStatic round-trip" {
            val original =
                classDiagram(name = "D") {
                    classOf(name = "Config") {
                        attribute(name = "maxRetries", type = "Int", defaultValue = "3", isStatic = true)
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "adversarial string content (quotes, backslashes, template-injection payloads, embedded newlines) round-trips byte-identically" {
            val trickyName = "Foo\"Bar\\Baz\${'$'}{1+1}\nLine2\r\tTabbed"
            val original =
                classDiagram(name = "D") {
                    classOf(name = "Widget") {
                        attribute(name = "field", type = "String", defaultValue = trickyName)
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        "comprehensive: every interpreter-supported gap combined into a single diagram round-trips exactly" {
            val original =
                classDiagram(name = "Order Domain") {
                    val status =
                        enumOf(name = "OrderStatus") {
                            literal(name = "DRAFT")
                            literal(name = "PAID")
                        }
                    val greeter = interfaceOf(name = "Greeter") { operation(name = "greet") }
                    val greeterImpl = classOf(name = "GreeterImpl")
                    val customer =
                        classOf(name = "Customer") {
                            attribute(name = "id", type = "UUID", visibility = Visibility.PUBLIC)
                        }
                    val order =
                        classOf(name = "Order") {
                            visibility = Visibility.PROTECTED
                            attribute(name = "status", type = status)
                            attribute(name = "total", type = "Int")
                            operation(name = "place") {
                                // NOTE: direction/defaultValue are deliberately left at their
                                // defaults here — they do NOT round-trip through the interpreter
                                // (see the dedicated documented-limitation test below).
                                parameter(name = "items", type = "List<String>")
                                parameter(name = "flags", type = "Int")
                                returns("OrderId")
                            }
                            operation(name = "cancel") {
                                visibility = Visibility.PRIVATE
                                isStatic = true
                            }
                            constraint(name = "hasTotal", body = "self.total >= 0")
                        }
                    val notifier = classOf(name = "NotificationService")
                    val auditLog = classOf(name = "AuditLog")

                    // NOTE: InterpreterUmlModelDslPrinter always emits generalizations
                    // before realizations (its fixed canonical dispatch order) — these
                    // two top-level calls must appear in that same order here.
                    generalization(specific = auditLog, general = notifier)
                    realization(implementing = greeterImpl, iface = greeter)
                    association(source = customer, target = order) {
                        name = "places"
                        aggregation = AggregationKind.COMPOSITE
                        source { navigable = false }
                        target {
                            multiplicity("0..*")
                            role = "orders"
                        }
                    }
                    dependency(client = order, supplier = notifier, name = "notifies")
                    comment(text = "General remark, not attached to anything.")
                    comment(text = "Encapsulates the order lifecycle.", firstAnchor = order)
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            reparse(printed) shouldBe original
        }

        // ── Documented-limitation tests ───────────────────────────────────────
        //
        // Not fidelity assertions — these pin down the printer's *known*,
        // honest boundary so a silent behavioural change is caught as a test
        // failure rather than discovered later in production.

        "a class with a stereotype fails interpretation entirely (proves the whole-script lexer blast radius)" {
            val original =
                classDiagram(name = "D") {
                    classOf(name = "Order") {
                        stereotypes += "entity"
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            // The printer must never emit `+=` at all for this to be a meaningful
            // regression guard on the printer itself, not just on the interpreter.
            printed.contains("+=") shouldBe false

            // Since the printer drops the stereotype, this script must actually
            // interpret successfully (it no longer references the unrepresentable
            // construct at all) — the point of this test is the inverse: proving
            // that if a `+=` construct DID leak through, the whole script would
            // fail, not just lose that one field. We demonstrate that directly by
            // hand-crafting the construct the printer must never produce.
            val handCraftedWithStereotype =
                printed.replaceFirst(
                    "classOf(name = \"Order\", id = \"Order\") {",
                    "classOf(name = \"Order\", id = \"Order\") {\n        stereotypes += \"entity\"",
                )
            val result = InterpreterScriptEvaluator.evaluate(source = handCraftedWithStereotype, fileName = "stereotype.kuml.kts")
            result.shouldBeInstanceOfFailure()
        }

        "a multi-anchor comment loses every anchor beyond the first (Success, but not equal to the original)" {
            val original =
                classDiagram(name = "D") {
                    val order = classOf(name = "Order")
                    val item = classOf(name = "OrderItem")
                    comment(text = "Applies to both.", firstAnchor = order, item)
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            printed shouldContain "TODO"
            printed shouldContain "1 more anchor"

            val reparsed = reparse(printed)
            reparsed shouldNotBe original
        }

        "a constraint's kind and contextOperation are dropped on read-back (Success, but not equal to the original)" {
            val original =
                classDiagram(name = "D") {
                    classOf(name = "Order") {
                        attribute(name = "total", type = "Int")
                        operation(name = "place")
                        constraint(
                            name = "PlacePre",
                            body = "self.total > 0",
                            kind = UmlConstraintKind.Precondition,
                            contextOperation = "place",
                        )
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            printed shouldContain "kind = UmlConstraintKind.Precondition"

            val reparsed = reparse(printed)
            reparsed shouldNotBe original
        }

        "a parameter's direction and defaultValue are dropped on read-back (Success, but not equal to the original)" {
            val original =
                classDiagram(name = "D") {
                    classOf(name = "Order") {
                        operation(name = "place") {
                            parameter(name = "flags", type = "Int", direction = ParameterDirection.OUT, defaultValue = "0")
                        }
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            printed shouldContain "direction = ParameterDirection.OUT"

            val reparsed = reparse(printed)
            reparsed shouldNotBe original
        }

        "a classifier-typed parameter falls back to a plain string type — referencedId is never printed" {
            val original =
                classDiagram(name = "D") {
                    val order = classOf(name = "Order")
                    classOf(name = "Repository") {
                        operation(name = "save") {
                            parameter(name = "order", type = order)
                        }
                    }
                }

            val printed = InterpreterUmlModelDslPrinter.print(original)
            printed shouldContain "parameter(name = \"order\", type = \"Order\")"
            printed.contains("referencedId") shouldBe false

            // Success, but the parameter's type.referencedId link is lost — the
            // reparsed diagram is NOT structurally equal to the original.
            val reparsed = reparse(printed)
            reparsed shouldNotBe original
        }
    })

/** Small helper so the stereotype-leak test above reads as a single assertion. */
private fun EvaluatedScript.shouldBeInstanceOfFailure() {
    require(this is EvaluatedScript.Failure) { "expected interpretation to fail, but got: $this" }
}
