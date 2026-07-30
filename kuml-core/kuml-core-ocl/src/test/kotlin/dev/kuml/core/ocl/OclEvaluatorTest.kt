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
    val ast = OclParser(tokens = tokens).parse()
    return OclEvaluator(self = self).eval(expr = ast)
}

private fun order(vararg attrNames: String): UmlClass =
    UmlClass(
        id = "Order",
        name = "Order",
        attributes =
            attrNames.mapIndexed { i, n ->
                UmlProperty(id = "Order::$n", name = n, type = UmlTypeRef(name = "String"), isStatic = i == 0)
            },
    )

class OclEvaluatorTest :
    FunSpec({

        // ── Real literals + mixed arithmetic ────────────────────────────────
        test("evaluates Real literal") {
            eval(self = order(), expr = "3.14") shouldBe 3.14
        }

        test("Int + Int stays Int") {
            eval(self = order(), expr = "1 + 2") shouldBe 3
        }

        test("Int + Real promotes to Real") {
            eval(self = order(), expr = "1 + 2.5") shouldBe 3.5
        }

        test("Real * Real stays Real") {
            eval(self = order(), expr = "2.0 * 3.0") shouldBe 6.0
        }

        test("division is always real division") {
            eval(self = order(), expr = "1 / 2") shouldBe 0.5
        }

        test("division by zero throws") {
            shouldThrow<OclEvaluationException> { eval(self = order(), expr = "1 / 0") }
        }

        test("unary minus works on Real") {
            eval(self = order(), expr = "-3.5") shouldBe -3.5
        }

        // ── let / if ─────────────────────────────────────────────────────────
        test("evaluates let expression") {
            eval(self = order(), expr = "let x = 2 in x + 3") shouldBe 5
        }

        test("evaluates nested let expressions") {
            eval(self = order(), expr = "let x = 2 in let y = 3 in x + y") shouldBe 5
        }

        test("evaluates if/then branch") {
            eval(self = order(), expr = "if true then 1 else 2 endif") shouldBe 1
        }

        test("evaluates if/else branch") {
            eval(self = order(), expr = "if false then 1 else 2 endif") shouldBe 2
        }

        test("if condition must be Boolean") {
            shouldThrow<OclEvaluationException> { eval(self = order(), expr = "if 1 then 1 else 2 endif") }
        }

        // ── Collection iterators ────────────────────────────────────────────
        test("select filters matching elements") {
            val cls = order("id", "name")
            val result = eval(self = cls, expr = "self.attributes->select(a | a.isStatic)") as List<*>
            result.size shouldBe 1
        }

        test("reject filters out matching elements") {
            val cls = order("id", "name")
            val result = eval(self = cls, expr = "self.attributes->reject(a | a.isStatic)") as List<*>
            result.size shouldBe 1
        }

        test("collect maps elements") {
            val cls = order("id", "name")
            val result = eval(self = cls, expr = "self.attributes->collect(a | a.name)") as List<*>
            result shouldBe listOf("id", "name")
        }

        test("any returns first matching element") {
            val cls = order("id", "name")
            val result = eval(self = cls, expr = "self.attributes->any(a | a.name = 'name')")
            (result as UmlProperty).name shouldBe "name"
        }

        test("one returns true iff exactly one element matches") {
            val cls = order("id", "name")
            eval(self = cls, expr = "self.attributes->one(a | a.isStatic)") shouldBe true
            eval(self = cls, expr = "self.attributes->one(a | true)") shouldBe false
        }

        test("isUnique detects duplicate mapped values") {
            val cls = order("id", "id")
            eval(self = cls, expr = "self.attributes->isUnique(a | a.name)") shouldBe false
        }

        test("sortedBy orders by mapped key") {
            val cls = order("b", "a")
            val result = eval(self = cls, expr = "self.attributes->sortedBy(a | a.name)") as List<*>
            (result.map { (it as UmlProperty).name }) shouldBe listOf("a", "b")
        }

        test("iterate accumulates a sum") {
            val cls = order("id", "name")
            eval(self = cls, expr = "self.attributes->iterate(a; acc = 0 | acc + 1)") shouldBe 2
        }

        test("sum adds numeric mapped values (Int stays Int)") {
            // No collection-literal syntax exists in this OCL subset (out of scope,
            // see V3.2.20 spec) — derive a List<Int> via collect() over a real
            // model collection instead of a literal.
            val cls = order("a", "b", "c")
            eval(self = cls, expr = "self.attributes->collect(x | self.attributes->size())->sum()") shouldBe 9
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
            OclEvaluator(self = order()).eval(expr = op, env = env) shouldBe 6.5
        }

        test("count counts matching values") {
            val cls = order("id", "id")
            eval(self = cls, expr = "self.attributes->collect(a | a.name)->count('id')") shouldBe 2
        }

        test("including adds an element") {
            val cls = order("id")
            val result = eval(self = cls, expr = "self.attributes->collect(a | a.name)->including('extra')") as List<*>
            result shouldBe listOf("id", "extra")
        }

        test("excluding removes matching elements") {
            val cls = order("id", "name")
            val result = eval(self = cls, expr = "self.attributes->collect(a | a.name)->excluding('id')") as List<*>
            result shouldBe listOf("name")
        }

        test("first and last return boundary elements") {
            val cls = order("a", "b", "c")
            eval(self = cls, expr = "self.attributes->collect(x | x.name)->first()") shouldBe "a"
            eval(self = cls, expr = "self.attributes->collect(x | x.name)->last()") shouldBe "c"
        }

        test("first on empty collection throws") {
            val cls = order()
            shouldThrow<OclEvaluationException> { eval(self = cls, expr = "self.attributes->first()") }
        }

        test("asSet removes duplicates") {
            val cls = order("id", "id", "name")
            val result = eval(self = cls, expr = "self.attributes->collect(a | a.name)->asSet()") as List<*>
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
                                type = UmlTypeRef(name = "UUID"),
                            ),
                        ),
                )
            val tokens = OclLexer.tokenize("self.attributes->size() > 0")
            val expr = OclParser(tokens = tokens).parse()
            val result = OclEvaluator(self = cls).eval(expr = expr)
            result shouldBe true
        }

        test("evaluates forAll") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(id = "Order::id", name = "id", type = UmlTypeRef(name = "UUID")),
                            UmlProperty(id = "Order::name", name = "name", type = UmlTypeRef(name = "String")),
                        ),
                )
            val tokens = OclLexer.tokenize("self.attributes->forAll(a | a.name <> 'status')")
            val expr = OclParser(tokens = tokens).parse()
            val result = OclEvaluator(self = cls).eval(expr = expr)
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
            val expr = OclParser(tokens = tokens).parse()
            val result = OclEvaluator(self = cls).eval(expr = expr)
            result shouldBe true
        }

        // ── Type operations (V3.2.22) ───────────────────────────────────────

        test("oclIsUndefined is true for null, false otherwise") {
            eval(self = order(), expr = "self.oclIsUndefined()") shouldBe false
            val op = OclExpression.TypeOp(receiver = OclExpression.VarRef("nope"), op = "oclIsUndefined")
            OclEvaluator(self = order()).eval(expr = op, env = mapOf("self" to order())) shouldBe true
        }

        test("oclIsInvalid is always false in this evaluator (no distinct invalid value)") {
            eval(self = order(), expr = "self.oclIsInvalid()") shouldBe false
        }

        test("oclIsTypeOf matches the exact classifier name only") {
            val cls = order()
            eval(self = cls, expr = "self.oclIsTypeOf(Order)") shouldBe true
            eval(self = cls, expr = "self.oclIsTypeOf(Other)") shouldBe false
        }

        test("oclIsKindOf matches the exact type and any ancestor via UmlGeneralization") {
            val base = UmlClass(id = "Base", name = "Base")
            val child = UmlClass(id = "Child", name = "Child")
            val gen = UmlGeneralization(id = "gen", specificId = "Child", generalId = "Base")
            val model = listOf(base, child, gen)

            val tokensSelf = OclParser(tokens = OclLexer.tokenize("self.oclIsKindOf(Child)")).parse()
            OclEvaluator(self = child, model = model).eval(expr = tokensSelf) shouldBe true

            val tokensBase = OclParser(tokens = OclLexer.tokenize("self.oclIsKindOf(Base)")).parse()
            OclEvaluator(self = child, model = model).eval(expr = tokensBase) shouldBe true

            val tokensOther = OclParser(tokens = OclLexer.tokenize("self.oclIsKindOf(Other)")).parse()
            OclEvaluator(self = child, model = model).eval(expr = tokensOther) shouldBe false
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
            val expr = OclParser(tokens = OclLexer.tokenize("self.oclIsKindOf(GrandParent)")).parse()
            OclEvaluator(self = child, model = model).eval(expr = expr) shouldBe true
        }

        test("oclAsType returns the receiver when the kind matches") {
            val base = UmlClass(id = "Base", name = "Base")
            val child = UmlClass(id = "Child", name = "Child")
            val gen = UmlGeneralization(id = "gen", specificId = "Child", generalId = "Base")
            val model = listOf(base, child, gen)
            val expr = OclParser(tokens = OclLexer.tokenize("self.oclAsType(Base)")).parse()
            OclEvaluator(self = child, model = model).eval(expr = expr) shouldBe child
        }

        test("oclAsType throws when the kind does not match") {
            val cls = order()
            val expr = OclParser(tokens = OclLexer.tokenize("self.oclAsType(Other)")).parse()
            shouldThrow<OclEvaluationException> { OclEvaluator(self = cls).eval(expr = expr) }
        }

        // ── Standard-library String operations (V3.2.24) ────────────────────

        test("String size returns character count") {
            eval(self = order(), expr = "'hello'.size()") shouldBe 5
        }

        test("String toUpper and toLower") {
            eval(self = order(), expr = "'Hello'.toUpper()") shouldBe "HELLO"
            eval(self = order(), expr = "'Hello'.toLower()") shouldBe "hello"
        }

        test("String concat appends the argument") {
            eval(self = order(), expr = "'foo'.concat('bar')") shouldBe "foobar"
        }

        test("String substring is 1-based and inclusive") {
            eval(self = order(), expr = "'pantry'.substring(1, 3)") shouldBe "pan"
            eval(self = order(), expr = "'pantry'.substring(4, 6)") shouldBe "try"
        }

        test("String substring out of bounds throws") {
            shouldThrow<OclEvaluationException> { eval(self = order(), expr = "'abc'.substring(0, 2)") }
            shouldThrow<OclEvaluationException> { eval(self = order(), expr = "'abc'.substring(1, 5)") }
        }

        test("String indexOf is 1-based, 0 when not found") {
            eval(self = order(), expr = "'hello world'.indexOf('world')") shouldBe 7
            eval(self = order(), expr = "'hello'.indexOf('xyz')") shouldBe 0
        }

        test("String isEmpty and notEmpty") {
            eval(self = order(), expr = "''.isEmpty()") shouldBe true
            eval(self = order(), expr = "'x'.notEmpty()") shouldBe true
        }

        test("String at returns the 1-based character") {
            eval(self = order(), expr = "'abc'.at(2)") shouldBe "b"
        }

        // ── Standard-library Integer/Real operations (V3.2.24) ──────────────

        test("Integer abs, floor, round") {
            eval(self = order(), expr = "(-5).abs()") shouldBe 5
            eval(self = order(), expr = "3.7.floor()") shouldBe 3
            eval(self = order(), expr = "3.5.round()") shouldBe 4
        }

        test("round is half-up, not Kotlin's half-to-even banker's rounding") {
            // kotlin.math.round(2.5) == 2.0 (rounds to even) — OCL spec requires
            // the *larger* of the two nearest integers for an exact .5 (OMG
            // OCL 2.4 §7.5.2), i.e. round-half-up.
            eval(self = order(), expr = "2.5.round()") shouldBe 3
            eval(self = order(), expr = "0.5.round()") shouldBe 1
        }

        test("Integer max and min") {
            eval(self = order(), expr = "3.max(7)") shouldBe 7
            eval(self = order(), expr = "3.min(7)") shouldBe 3
        }

        test("Integer mod and div") {
            eval(self = order(), expr = "7.mod(3)") shouldBe 1
            eval(self = order(), expr = "7.div(3)") shouldBe 2
        }

        test("mod and div by zero throw") {
            shouldThrow<OclEvaluationException> { eval(self = order(), expr = "7.mod(0)") }
            shouldThrow<OclEvaluationException> { eval(self = order(), expr = "7.div(0)") }
        }

        // ── @pre snapshot (V3.2.22) ──────────────────────────────────────────

        test("@pre resolves via the explicit preSnapshot env when provided") {
            val cls = order("id")
            val expr = OclParser(tokens = OclLexer.tokenize("self.attributes->size()@pre")).parse()
            val preState = mapOf("self" to order("id", "name"))
            OclEvaluator(self = cls, preSnapshot = preState).eval(expr = expr) shouldBe 2
        }

        test("@pre falls back to current env when no preSnapshot is given (static-validation no-op)") {
            val cls = order("id", "name")
            val expr = OclParser(tokens = OclLexer.tokenize("self.attributes->size()@pre")).parse()
            OclEvaluator(self = cls).eval(expr = expr) shouldBe 2
        }
    })
