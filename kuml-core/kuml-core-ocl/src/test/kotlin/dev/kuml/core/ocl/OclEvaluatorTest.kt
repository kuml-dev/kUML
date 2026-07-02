package dev.kuml.core.ocl

import dev.kuml.core.ocl.ast.OclExpression
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun eval(
    self: Any,
    expr: String,
): Any? {
    val tokens = OclLexer.tokenize(expr)
    val ast = OclParser(tokens).parse()
    return OclEvaluator(self).eval(ast)
}

private fun order(vararg attrNames: String): UmlClass =
    UmlClass(
        id = "Order",
        name = "Order",
        attributes =
            attrNames.mapIndexed { i, n ->
                UmlProperty(id = "Order::$n", name = n, type = UmlTypeRef("String"), isStatic = i == 0)
            },
    )

class OclEvaluatorTest :
    FunSpec({

        // ── Real literals + mixed arithmetic ────────────────────────────────
        test("evaluates Real literal") {
            eval(order(), "3.14") shouldBe 3.14
        }

        test("Int + Int stays Int") {
            eval(order(), "1 + 2") shouldBe 3
        }

        test("Int + Real promotes to Real") {
            eval(order(), "1 + 2.5") shouldBe 3.5
        }

        test("Real * Real stays Real") {
            eval(order(), "2.0 * 3.0") shouldBe 6.0
        }

        test("division is always real division") {
            eval(order(), "1 / 2") shouldBe 0.5
        }

        test("division by zero throws") {
            shouldThrow<OclEvaluationException> { eval(order(), "1 / 0") }
        }

        test("unary minus works on Real") {
            eval(order(), "-3.5") shouldBe -3.5
        }

        // ── let / if ─────────────────────────────────────────────────────────
        test("evaluates let expression") {
            eval(order(), "let x = 2 in x + 3") shouldBe 5
        }

        test("evaluates nested let expressions") {
            eval(order(), "let x = 2 in let y = 3 in x + y") shouldBe 5
        }

        test("evaluates if/then branch") {
            eval(order(), "if true then 1 else 2 endif") shouldBe 1
        }

        test("evaluates if/else branch") {
            eval(order(), "if false then 1 else 2 endif") shouldBe 2
        }

        test("if condition must be Boolean") {
            shouldThrow<OclEvaluationException> { eval(order(), "if 1 then 1 else 2 endif") }
        }

        // ── Collection iterators ────────────────────────────────────────────
        test("select filters matching elements") {
            val cls = order("id", "name")
            val result = eval(cls, "self.attributes->select(a | a.isStatic)") as List<*>
            result.size shouldBe 1
        }

        test("reject filters out matching elements") {
            val cls = order("id", "name")
            val result = eval(cls, "self.attributes->reject(a | a.isStatic)") as List<*>
            result.size shouldBe 1
        }

        test("collect maps elements") {
            val cls = order("id", "name")
            val result = eval(cls, "self.attributes->collect(a | a.name)") as List<*>
            result shouldBe listOf("id", "name")
        }

        test("any returns first matching element") {
            val cls = order("id", "name")
            val result = eval(cls, "self.attributes->any(a | a.name = 'name')")
            (result as UmlProperty).name shouldBe "name"
        }

        test("one returns true iff exactly one element matches") {
            val cls = order("id", "name")
            eval(cls, "self.attributes->one(a | a.isStatic)") shouldBe true
            eval(cls, "self.attributes->one(a | true)") shouldBe false
        }

        test("isUnique detects duplicate mapped values") {
            val cls = order("id", "id")
            eval(cls, "self.attributes->isUnique(a | a.name)") shouldBe false
        }

        test("sortedBy orders by mapped key") {
            val cls = order("b", "a")
            val result = eval(cls, "self.attributes->sortedBy(a | a.name)") as List<*>
            (result.map { (it as UmlProperty).name }) shouldBe listOf("a", "b")
        }

        test("iterate accumulates a sum") {
            val cls = order("id", "name")
            eval(cls, "self.attributes->iterate(a; acc = 0 | acc + 1)") shouldBe 2
        }

        test("sum adds numeric mapped values (Int stays Int)") {
            // No collection-literal syntax exists in this OCL subset (out of scope,
            // see V3.2.20 spec) — derive a List<Int> via collect() over a real
            // model collection instead of a literal.
            val cls = order("a", "b", "c")
            eval(cls, "self.attributes->collect(x | self.attributes->size())->sum()") shouldBe 9
        }

        test("sum over a Real-valued list promotes result to Real") {
            // No collection-literal syntax exists in this OCL subset (out of scope,
            // see V3.2.20 spec), so a List<Double> is exercised directly against the
            // evaluator's `sum` handling rather than via a parsed expression.
            val op =
                OclExpression.CollectionOp(
                    receiver = OclExpression.VarRef("nums"),
                    op = "sum",
                )
            val env = mapOf("self" to order(), "nums" to listOf(1.5, 2.0, 3.0))
            OclEvaluator(order()).eval(op, env) shouldBe 6.5
        }

        test("count counts matching values") {
            val cls = order("id", "id")
            eval(cls, "self.attributes->collect(a | a.name)->count('id')") shouldBe 2
        }

        test("including adds an element") {
            val cls = order("id")
            val result = eval(cls, "self.attributes->collect(a | a.name)->including('extra')") as List<*>
            result shouldBe listOf("id", "extra")
        }

        test("excluding removes matching elements") {
            val cls = order("id", "name")
            val result = eval(cls, "self.attributes->collect(a | a.name)->excluding('id')") as List<*>
            result shouldBe listOf("name")
        }

        test("first and last return boundary elements") {
            val cls = order("a", "b", "c")
            eval(cls, "self.attributes->collect(x | x.name)->first()") shouldBe "a"
            eval(cls, "self.attributes->collect(x | x.name)->last()") shouldBe "c"
        }

        test("first on empty collection throws") {
            val cls = order()
            shouldThrow<OclEvaluationException> { eval(cls, "self.attributes->first()") }
        }

        test("asSet removes duplicates") {
            val cls = order("id", "id", "name")
            val result = eval(cls, "self.attributes->collect(a | a.name)->asSet()") as List<*>
            result.size shouldBe 2
        }

        test("evaluates size comparison to true") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "Order::id",
                                name = "id",
                                type = UmlTypeRef("UUID"),
                            ),
                        ),
                )
            val tokens = OclLexer.tokenize("self.attributes->size() > 0")
            val expr = OclParser(tokens).parse()
            val result = OclEvaluator(cls).eval(expr)
            result shouldBe true
        }

        test("evaluates forAll") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(id = "Order::id", name = "id", type = UmlTypeRef("UUID")),
                            UmlProperty(id = "Order::name", name = "name", type = UmlTypeRef("String")),
                        ),
                )
            val tokens = OclLexer.tokenize("self.attributes->forAll(a | a.name <> 'status')")
            val expr = OclParser(tokens).parse()
            val result = OclEvaluator(cls).eval(expr)
            result shouldBe true
        }

        test("evaluates implies") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    isAbstract = true,
                    operations =
                        listOf(
                            UmlOperation(id = "Order::confirm", name = "confirm"),
                        ),
                )
            val tokens = OclLexer.tokenize("self.isAbstract implies self.operations->notEmpty()")
            val expr = OclParser(tokens).parse()
            val result = OclEvaluator(cls).eval(expr)
            result shouldBe true
        }

        // ── Type operations (V3.2.22) ───────────────────────────────────────

        test("oclIsUndefined is true for null, false otherwise") {
            eval(order(), "self.oclIsUndefined()") shouldBe false
            val op = OclExpression.TypeOp(OclExpression.VarRef("nope"), "oclIsUndefined")
            OclEvaluator(order()).eval(op, mapOf("self" to order())) shouldBe true
        }

        test("oclIsInvalid is always false in this evaluator (no distinct invalid value)") {
            eval(order(), "self.oclIsInvalid()") shouldBe false
        }

        test("oclIsTypeOf matches the exact classifier name only") {
            val cls = order()
            eval(cls, "self.oclIsTypeOf(Order)") shouldBe true
            eval(cls, "self.oclIsTypeOf(Other)") shouldBe false
        }

        test("oclIsKindOf matches the exact type and any ancestor via UmlGeneralization") {
            val base = UmlClass(id = "Base", name = "Base")
            val child = UmlClass(id = "Child", name = "Child")
            val gen = UmlGeneralization(id = "gen", specificId = "Child", generalId = "Base")
            val model = listOf(base, child, gen)

            val tokensSelf = OclParser(OclLexer.tokenize("self.oclIsKindOf(Child)")).parse()
            OclEvaluator(child, model).eval(tokensSelf) shouldBe true

            val tokensBase = OclParser(OclLexer.tokenize("self.oclIsKindOf(Base)")).parse()
            OclEvaluator(child, model).eval(tokensBase) shouldBe true

            val tokensOther = OclParser(OclLexer.tokenize("self.oclIsKindOf(Other)")).parse()
            OclEvaluator(child, model).eval(tokensOther) shouldBe false
        }

        test("oclIsKindOf walks a multi-level generalization chain") {
            val grandparent = UmlClass(id = "GP", name = "GrandParent")
            val parent = UmlClass(id = "P", name = "Parent")
            val child = UmlClass(id = "C", name = "Child")
            val model =
                listOf(
                    grandparent,
                    parent,
                    child,
                    UmlGeneralization(id = "g1", specificId = "C", generalId = "P"),
                    UmlGeneralization(id = "g2", specificId = "P", generalId = "GP"),
                )
            val expr = OclParser(OclLexer.tokenize("self.oclIsKindOf(GrandParent)")).parse()
            OclEvaluator(child, model).eval(expr) shouldBe true
        }

        test("oclAsType returns the receiver when the kind matches") {
            val base = UmlClass(id = "Base", name = "Base")
            val child = UmlClass(id = "Child", name = "Child")
            val gen = UmlGeneralization(id = "gen", specificId = "Child", generalId = "Base")
            val model = listOf(base, child, gen)
            val expr = OclParser(OclLexer.tokenize("self.oclAsType(Base)")).parse()
            OclEvaluator(child, model).eval(expr) shouldBe child
        }

        test("oclAsType throws when the kind does not match") {
            val cls = order()
            val expr = OclParser(OclLexer.tokenize("self.oclAsType(Other)")).parse()
            shouldThrow<OclEvaluationException> { OclEvaluator(cls).eval(expr) }
        }

        // ── @pre snapshot (V3.2.22) ──────────────────────────────────────────

        test("@pre resolves via the explicit preSnapshot env when provided") {
            val cls = order("id")
            val expr = OclParser(OclLexer.tokenize("self.attributes->size()@pre")).parse()
            val preState = mapOf("self" to order("id", "name"))
            OclEvaluator(cls, preSnapshot = preState).eval(expr) shouldBe 2
        }

        test("@pre falls back to current env when no preSnapshot is given (static-validation no-op)") {
            val cls = order("id", "name")
            val expr = OclParser(OclLexer.tokenize("self.attributes->size()@pre")).parse()
            OclEvaluator(cls).eval(expr) shouldBe 2
        }
    })
