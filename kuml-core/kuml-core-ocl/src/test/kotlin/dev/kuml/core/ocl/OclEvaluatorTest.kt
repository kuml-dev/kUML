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

        // ── Regression: 'oclIsUndefined'/'oclIsInvalid' must not taint the very ──
        // "was this provided?" answer they exist to give (MINOR finding, branch
        // fix/activity-guard-negation). The receiver navigation that resolves a
        // missing variable to `null` sets [OclEvaluator.referencedMissingVariable]
        // as a side effect — correct for every other operation, but backwards
        // here: "the value is missing" is exactly what `oclIsUndefined()` is
        // asking, so a receiver that is `null` *because* it was never provided
        // must not make the resulting `true` untrustworthy. Empirically, a guard
        // `vars.cancelReason.oclIsUndefined()` with no `cancelReason` in `vars`
        // returned `Failed` instead of `True` before this fix.

        test("'oclIsUndefined()' on a missing VarRef receiver stays untainted") {
            val evaluator = OclEvaluator(self = order())
            val op = OclExpression.TypeOp(receiver = OclExpression.VarRef("nope"), op = "oclIsUndefined")
            val value = evaluator.eval(expr = op, env = mapOf("self" to order()))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'oclIsUndefined()' on a missing map-key (dot-navigation) receiver stays untainted") {
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.cancelReason.oclIsUndefined()",
                    self = order(),
                    env = mapOf("vars" to mapOf("other" to 1)),
                )
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe false
        }

        test("'oclIsUndefined()' on a present-but-null receiver stays untainted (control)") {
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.cancelReason.oclIsUndefined()",
                    self = order(),
                    env = mapOf("vars" to mapOf("cancelReason" to null)),
                )
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe false
        }

        test("'oclIsUndefined()' is false and untainted for a genuinely present receiver (control)") {
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.cancelReason.oclIsUndefined()",
                    self = order(),
                    env = mapOf("vars" to mapOf("cancelReason" to "customer")),
                )
            tracked.value shouldBe false
            tracked.referencedMissingVariable shouldBe false
        }

        test(
            "'oclIsTypeOf' on a missing map-key receiver still taints " +
                "(control: only oclIsUndefined/oclIsInvalid discard receiver taint)",
        ) {
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.missing.oclIsTypeOf(Order)",
                    self = order(),
                    env = mapOf("vars" to mapOf("other" to 1)),
                )
            tracked.value shouldBe false
            tracked.referencedMissingVariable shouldBe true
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

        // ── referencedMissingVariable tracking (fail-open '<>'/missing-var fix) ──

        test("referencedMissingVariable is false for a VarRef present in env, even when its value is null") {
            val evaluator = OclEvaluator(self = order())
            evaluator.eval(expr = OclExpression.VarRef(name = "x"), env = mapOf("self" to order(), "x" to null))
            evaluator.referencedMissingVariable shouldBe false
        }

        test("referencedMissingVariable is true for a VarRef absent from env") {
            val evaluator = OclEvaluator(self = order())
            evaluator.eval(expr = OclExpression.VarRef(name = "missing"), env = mapOf("self" to order()))
            evaluator.referencedMissingVariable shouldBe true
        }

        test("referencedMissingVariable is true after evaluating '<>' against a missing variable") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("x <> 1")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order()))
            value shouldBe true // the fail-open value this whole fix guards against trusting
            evaluator.referencedMissingVariable shouldBe true
        }

        test("referencedMissingVariable stays false after evaluating '<>' against a present variable") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("x <> 1")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "x" to 2))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        // ── referencedMissingVariable via dot-navigation on a Map receiver ───────
        // (RUNDE-2-BEFUND #1: 'vars.x <> 1' / 'event.x <> 1' bypassed the VarRef-only
        // tracking above via OclExpression.Navigate -> UmlPropertyAccessor's Map branch.)

        test("referencedMissingVariable is true after 'vars.missing <> 1' — dot-navigation on a Map receiver") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.missing <> 1")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to emptyMap<String, Any?>()))
            value shouldBe true // the fail-open value this fix guards against trusting
            evaluator.referencedMissingVariable shouldBe true
        }

        test("referencedMissingVariable is true after 'event.approved <> true' when 'approved' is absent from the event map") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("event.approved <> true")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "event" to mapOf("other" to 1)))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("referencedMissingVariable stays false after 'vars.status <> 1' when 'status' is present, even if null") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.status <> 1")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to mapOf("status" to null)))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("referencedMissingVariable is true after a zero-arg operation call on a Map receiver misses the key") {
            // Covers evalOperationCall's PropertyAccessor fallback for Map receivers
            // (e.g. 'vars.getStatus()'), the second gap named in RUNDE-2-BEFUND #1.
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.getStatus() <> 1")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to emptyMap<String, Any?>()))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        // ── Short-circuit-aware referencedMissingVariable for and/or/implies ────
        // (RUNDE-2-BEFUND #2: eager evaluation of both operands tainted a
        // legitimately-true 'or'/'implies' result with a missing variable on the
        // side that never actually determined the outcome.)

        test("'or' with a true left operand does not taint the result via a missing right operand") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("urgent or vip")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "urgent" to true))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        // RUNDE-2-BEFUND #2 (this branch): the mirror image of the case above.
        // A missing *left* operand no longer taints a 'true' result once the
        // *right* operand alone — untainted — already establishes it ("X or
        // true" is true regardless of X). Previously this asserted
        // `referencedMissingVariable shouldBe true`, which was exactly the
        // over-tainting bug the finding reported (operand order shouldn't
        // change whether "urgent or vip" is trustworthy when one side is a
        // trustworthy `true`).
        test("'or' with a missing left operand does not taint the result when the right operand alone is a trustworthy true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("urgent or vip")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vip" to true))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'or' with a missing left operand and a false right operand still taints the (false) result") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("urgent or vip")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vip" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'implies' with a false left operand does not taint the result via a missing right operand") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("blocked implies override")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "blocked" to false))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        // Symmetric 'implies' counterpart (RUNDE-2-BEFUND #2, this branch):
        // "X implies true" is true regardless of X, so a *missing* left
        // operand should not taint the result once the right operand alone —
        // untainted — already establishes it.
        test("'implies' with a missing left operand does not taint the result when the right operand alone is a trustworthy true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vip implies urgent")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "urgent" to true))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'implies' with a missing left operand and a false right operand still taints the (true) result") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vip implies urgent")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "urgent" to false))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'and' with a false left operand does not taint the result via a missing right operand") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("allow and vip")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "allow" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'and' with both operands present still requires both for the missing-variable check") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("urgent and vip")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "urgent" to true, "vip" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        // ── Regression: evalIsolated must not lose taint across an exception ───
        // (MAJOR finding, this branch: 'or'/'implies' isolate each operand's own
        // taint via evalIsolated so a missing variable on a branch that never
        // determines the result does not spuriously taint an otherwise-true
        // result -- but the isolation ran the operand unguarded, so an
        // OclEvaluationException thrown mid-operand (e.g. a dot-navigation type
        // mismatch inside a `closure(...)` body, which evalClosure catches and
        // treats as "no successor", never letting the exception reach the
        // caller) skipped the restoration step entirely and permanently reset
        // the flag to `false` -- wiping out whatever taint the *outer*,
        // already-tainted evaluation had accumulated before this isolated
        // operand even started. Result: a `true` value that still genuinely
        // depended on a missing variable came back untainted (fail-open).

        test("an exception inside an 'or' operand evaluated via evalIsolated does not erase the outer taint") {
            val env = mapOf("vars" to mapOf("items" to listOf(1, 2)))
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.missing <> 1 and vars.items->closure(i | i.nothere.deep or true)->isEmpty()",
                    self = order(),
                    env = env,
                )
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe true
        }

        test("an exception inside an 'implies' operand evaluated via evalIsolated does not erase the outer taint") {
            val env = mapOf("vars" to mapOf("items" to listOf(1, 2)))
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.missing <> 1 and vars.items->closure(i | i.nothere.deep implies true)->isEmpty()",
                    self = order(),
                    env = env,
                )
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe true
        }

        test("control: the same outer taint survives an 'and' + collection-size right operand with no closure exception") {
            val env = mapOf("vars" to mapOf("items" to listOf(1, 2)))
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.missing <> 1 and vars.items->size() = 2",
                    self = order(),
                    env = env,
                )
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe true
        }

        // ── Regression: 'implies' must not treat a present-but-non-Boolean left ──
        // operand as a concrete `false` (MAJOR finding, this branch). The old
        // condition `lVal != true && !lTainted` matched not only a genuine
        // Boolean `false` left operand but *any* left operand the `as? Boolean`
        // cast turned into `null` — a String, an Int, or a present-and-`null`
        // value all qualify, none of which is "concretely false" in the sense
        // the OCL "false implies invalid = true" rule requires. That silently
        // skipped the right operand (never evaluating or even navigating into
        // it) and returned an untainted `true`, regardless of what the right
        // operand actually depended on.

        test("'implies' with a String left operand does not short-circuit and taints when the right operand is missing") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag implies approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "true"))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'implies' with an Int left operand does not short-circuit and taints when the right operand is missing") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag implies approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to 1))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'implies' with a present-but-null left operand does not short-circuit and taints when the right operand is missing") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag implies approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to null))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'implies' with a non-Boolean left operand still returns an untainted true when the right operand alone is trustworthy") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag implies approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "true", "approved" to true))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'implies' with a non-Boolean left operand propagates an exception from the right operand instead of an untainted true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag implies num.foo")).parse()
            shouldThrow<OclEvaluationException> {
                evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "true", "num" to 5))
            }
        }

        test("'implies' with a concrete Boolean false left operand still short-circuits without navigating a throwing right operand") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag implies num.foo")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to false, "num" to 5))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        // ── Regression: a sibling operand's own isolated taint must survive a ───
        // throwing counterpart (MINOR finding, this branch). [evalIsolated]'s own
        // catch only restores its *own* `savedFlag` (the taint from before this
        // 'or'/'implies' node started) — it has no way to know about a sibling
        // operand's `lTainted`/`rTainted`, which lives only in a local variable
        // at the 'or'/'implies' call site. Without re-merging that local taint
        // back in before the sibling's evaluation is allowed to throw, an
        // exception from one operand silently discarded a taint the *other*
        // operand had already legitimately recorded.

        test("'or' preserves a tainted-but-false left operand's taint when the right operand throws") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("missingx = 1 or num.foo")).parse()
            shouldThrow<OclEvaluationException> {
                evaluator.eval(expr = expr, env = mapOf("self" to order(), "num" to 5))
            }
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'implies' preserves a missing left operand's taint when the right operand throws") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("missingx implies num.foo")).parse()
            shouldThrow<OclEvaluationException> {
                evaluator.eval(expr = expr, env = mapOf("self" to order(), "num" to 5))
            }
            evaluator.referencedMissingVariable shouldBe true
        }

        test("an exception inside an 'or' operand nested in a closure does not erase the left operand's own taint") {
            val env = mapOf("vars" to mapOf("items" to listOf(1, 2)))
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.items->closure(i | missingx = 1 or i.nothere.deep)->isEmpty()",
                    self = order(),
                    env = env,
                )
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe true
        }

        test("an exception in the unresolved-left branch of 'implies' in a closure does not erase the left's own taint") {
            val env = mapOf("vars" to mapOf("items" to listOf(1, 2)))
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.items->closure(i | missingx implies i.nothere.deep)->isEmpty()",
                    self = order(),
                    env = env,
                )
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe true
        }

        test("an exception inside the true-left branch of 'implies' nested in a closure does not erase the left operand's own taint") {
            val env = mapOf("vars" to mapOf("items" to listOf(1, 2)))
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.items->closure(i | (missingx <> 1) implies i.nothere.deep)->isEmpty()",
                    self = order(),
                    env = env,
                )
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe true
        }

        // Unlike its 'or'/'implies' siblings above, the 'and' arm's right
        // operand was never wrapped in try/catch when it was switched to
        // isolate the *left* operand (MAJOR finding, this branch): `lTainted`
        // lives only in a local variable and is otherwise merged into
        // [OclEvaluator.referencedMissingVariable] solely by the branches
        // below the eval call, so a throwing right operand (e.g. the
        // dot-navigation type mismatch [evalClosure] swallows) skipped all of
        // them and let `lTainted` — the left operand's own, already-recorded
        // taint — vanish instead of surviving the exception, turning a
        // previously fail-closed path fail-open.
        test("an exception inside an 'and' operand nested in a closure does not erase the left operand's own taint") {
            val env = mapOf("vars" to mapOf("items" to listOf(1, 2)))
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.items->closure(i | missingx and i.nothere.deep)->isEmpty()",
                    self = order(),
                    env = env,
                )
            tracked.value shouldBe true
            tracked.referencedMissingVariable shouldBe true
        }

        // ── Regression: 'and' must not treat a present-but-non-Boolean left ──────
        // operand as a concrete `false` (MAJOR finding, this branch — the same
        // defect the 'implies' arm was fixed for above, present in 'and' too).
        // The old condition `l != true` matched not only a genuine Boolean
        // `false` left operand but *any* left operand the `as? Boolean` cast
        // turned into `null` — a String, an Int, or a present-and-`null` value
        // all qualify, none of which is "concretely false" in the sense the OCL
        // "false and invalid = false" rule requires. That silently skipped the
        // right operand (never evaluating or even navigating into it) and
        // returned an untainted `false`, regardless of what the right operand
        // actually depended on — a `false` that escalates to an untainted
        // `true` (fail-open) as soon as it is negated or compared.

        test("'and' with a String left operand does not short-circuit and taints when the right operand is missing") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes"))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'and' with an Int left operand does not short-circuit and taints when the right operand is missing") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to 1))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'and' with a present-but-null left operand does not short-circuit and taints when the right operand is missing") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to null))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'and' with a missing left operand still taints when the right operand is also missing") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order()))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'and' with a non-Boolean left operand returns an untainted false when the right operand alone is concretely false") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes", "approved" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'and' with both operands concretely true returns an untainted true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to true, "approved" to true))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'and' with a non-Boolean left operand propagates an exception from the right operand instead of a silent false") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and num.foo")).parse()
            shouldThrow<OclEvaluationException> {
                evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes", "num" to 5))
            }
        }

        test("'and' with a concrete Boolean false left operand still short-circuits without navigating a throwing right operand") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and num.foo")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to false, "num" to 5))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        // ── Regression: 'and' must not leak a missing LEFT operand's own taint ──
        // into a result the RIGHT operand alone concretely decides (MAJOR
        // finding, branch fix/activity-guard-negation). Unlike the
        // present-but-wrongly-typed cases above, a genuinely *missing* left
        // operand set [referencedMissingVariable] as a side effect of the
        // unisolated `eval()` call that used to evaluate it — so even though
        // "invalid and false = false" is trustworthy per OCL's non-strict
        // semantics, the flag stayed `true` from evaluating the left operand
        // before the right operand's concrete `false` ever got a chance to
        // decide anything. Empirically: a guard
        // `not (vars.approvalRequired and vars.approved)` with only
        // `approved = false` provided returned `Failed` instead of `True` on
        // this branch; on `master` (before the taint tracking in this branch
        // existed) the same guard returned `True`.

        test("'and' with a missing (VarRef) left operand returns an untainted false when the right operand alone is concretely false") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("approvalRequired and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "approved" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'not (missing and false)' returns an untainted True, mirroring the fixed guard-level regression") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not (approvalRequired and approved)")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "approved" to false))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test(
            "'and' with a missing map-key (dot-navigation) left operand returns an untainted false " +
                "when the right operand alone is concretely false",
        ) {
            val tracked =
                OclExpressions.evaluateTracked(
                    expression = "vars.approvalRequired and vars.approved",
                    self = order(),
                    env = mapOf("vars" to mapOf("approved" to false)),
                )
            tracked.value shouldBe false
            tracked.referencedMissingVariable shouldBe false
        }

        test("'and' with a missing left operand still taints when the right operand is concretely true") {
            // Contrast with the two tests above: here neither the `l == false`
            // nor the `r == false` shortcut applies (the missing left operand
            // resolves to `null`, not a concrete `false`), so there is no
            // independently-trustworthy operand to fall back on and the
            // catch-all "unresolved" branch must still taint the result —
            // isolating the left operand's own evaluation must not make this
            // case silently untainted.
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("approvalRequired and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "approved" to true))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'not' escalation: a non-Boolean left operand in 'and' no longer produces an untainted true when negated") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not (flag and approved)")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes"))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        // ══ Mirrored silent-narrowing gap: the RIGHT operand of 'and'/'implies' ═
        //    and the "unknown ⇒ false" arms of 'or' (branch
        //    fix/activity-guard-negation, follow-up to the sweep above). The
        //    sweep audited `as?`-narrowing *call sites* and fixed the *left*-
        //    operand instance of "a present-but-non-Boolean operand silently
        //    reads as untainted false/true" for 'and'/'implies' — but the
        //    defect actually lives in the *conditions that consume* a
        //    narrowed `null`, not in the cast itself, so the mirror-image gap
        //    on the operand the sweep did not look at stayed open. Fixed by
        //    spelling out the explicit three-valued (Kleene/OCL-"invalid")
        //    truth table per operator instead of special-casing one operand.

        test("'and' with a true left operand and a present-but-non-Boolean right operand taints the (false) result") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to true, "approved" to "yes"))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'and' with a true left operand and a present-but-null right operand taints the (false) result") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to true, "approved" to null))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'or' with a non-Boolean left operand and a concretely false right operand taints the (false) result") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag or approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes", "approved" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'or' with a concretely false left operand and a non-Boolean right operand taints the (false) result") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag or approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to false, "approved" to "yes"))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'implies' with a true left operand and a non-Boolean right operand taints the (false) result") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("a implies b")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "a" to true, "b" to "yes"))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'not (flag and approved)' with a true flag and a non-Boolean approved no longer produces an untainted true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not (flag and approved)")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to true, "approved" to "yes"))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'not (flag or approved)' with a false flag and a non-Boolean approved no longer produces an untainted true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not (flag or approved)")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to false, "approved" to "yes"))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'not (a implies b)' with a true a and a non-Boolean b no longer produces an untainted true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not (a implies b)")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "a" to true, "b" to "yes"))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        // ── Controls: must stay untainted (guards against over-tightening) ─────

        test("'and' with a non-Boolean left operand and a concretely false right operand stays untainted (control)") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes", "approved" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'or' with both operands concretely false stays untainted") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag or approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to false, "approved" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'or' with a non-Boolean left operand and a concretely true right operand stays untainted") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag or approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes", "approved" to true))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'implies' with a concretely false left operand still returns an untainted true regardless of a non-Boolean right operand") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag implies approved")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to false, "approved" to "yes"))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'implies' with a true left operand and a concretely false right operand stays untainted (control)") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("a implies b")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "a" to true, "b" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'and'/'or'/'implies' with all-Boolean operands: full truth table stays untainted") {
            val bools = listOf(true, false)
            for (l in bools) {
                for (r in bools) {
                    val env = mapOf("self" to order(), "l" to l, "r" to r)

                    val andEvaluator = OclEvaluator(self = order())
                    val andExpr = OclParser(tokens = OclLexer.tokenize("l and r")).parse()
                    andEvaluator.eval(expr = andExpr, env = env) shouldBe (l && r)
                    andEvaluator.referencedMissingVariable shouldBe false

                    val orEvaluator = OclEvaluator(self = order())
                    val orExpr = OclParser(tokens = OclLexer.tokenize("l or r")).parse()
                    orEvaluator.eval(expr = orExpr, env = env) shouldBe (l || r)
                    orEvaluator.referencedMissingVariable shouldBe false

                    val impliesEvaluator = OclEvaluator(self = order())
                    val impliesExpr = OclParser(tokens = OclLexer.tokenize("l implies r")).parse()
                    impliesEvaluator.eval(expr = impliesExpr, env = env) shouldBe (!l || r)
                    impliesEvaluator.referencedMissingVariable shouldBe false
                }
            }
        }

        test("'and' with a true left operand propagates a throwing right operand") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag and num.foo")).parse()
            shouldThrow<OclEvaluationException> {
                evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to true, "num" to 5))
            }
        }

        test("'or' with a non-Boolean left operand propagates a throwing right operand instead of a silent false") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("flag or num.foo")).parse()
            shouldThrow<OclEvaluationException> {
                evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes", "num" to 5))
            }
        }

        // ══ Systematic sweep of the whole "silently-narrowed sub-expression ══════
        //    loses the fail-closed taint" bug class (this branch).
        //
        // The preceding blocks fixed one operator per adversarial review round
        // ('implies', then 'or', then 'and'). The blocks below are the result of
        // auditing *every* remaining construct in OclEvaluator that narrows a
        // Boolean-like or collection-like sub-expression result with a silent
        // `as?` cast, and could therefore hand back a trustworthy-*looking*
        // (untainted) value that was really built on data that was missing or
        // arrived with the wrong type.

        // ── (1) 'implies' with a *tainted* Boolean-true left operand ────────────
        // `evalIsolated` deliberately keeps each operand's own taint out of the
        // shared flag so the call site can decide whether it matters. The
        // `lVal == true` arm dropped `lTainted` unconditionally — correct when
        // the right operand alone establishes a `true` ("X implies true" is true
        // for any X), but wrong when the right operand makes the result `false`:
        // had the left operand's real (unknown) value been `false`, OCL's
        // "false implies X = true" rule would have produced `true` instead. The
        // untainted `false` then escalates to an untainted `True` guard as soon
        // as it is negated.

        test("'implies' keeps a tainted-true left operand's taint when the right operand makes the result false") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("(vars.missing <> 1) implies vars.b")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to mapOf("b" to false)))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'not ((vars.missing <> 1) implies vars.b)' no longer escalates to an untainted true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not ((vars.missing <> 1) implies vars.b)")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to mapOf("b" to false)))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'implies' with a tainted-true left operand stays untainted when the right operand alone is a trustworthy true") {
            // "X implies true" is true for every X, so the left operand's taint
            // is genuinely irrelevant here — this must NOT become over-strict.
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("(vars.missing <> 1) implies vars.b")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to mapOf("b" to true)))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'implies' with an untainted true left operand and a false right operand stays untainted") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("a implies b")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "a" to true, "b" to false))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'implies' with a tainted-true left operand propagates a throwing right operand without losing the taint") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("(vars.missing <> 1) implies num.foo")).parse()
            shouldThrow<OclEvaluationException> {
                evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to emptyMap<String, Any?>(), "num" to 5))
            }
            evaluator.referencedMissingVariable shouldBe true
        }

        // ── (2) Boolean-valued iterator bodies (forAll/exists/select/reject/ ────
        //    any/one). `bodyOf(item) as? Boolean ?: false` turned *any*
        //    non-Boolean body result — a String, an Int, a present `null` — into
        //    a silent, untainted `false`, exactly the same `as? Boolean` defect
        //    the 'and'/'implies' arms were fixed for. `forAll` then reports an
        //    untainted `false` (which negates to an untainted `true`), `reject`
        //    keeps the element (so `->notEmpty()` is an untainted `true`), and
        //    `one` counts it as non-matching.

        test("a non-Boolean 'forAll' body taints instead of silently counting as false") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not (vars.items->forAll(i | i.flag))")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("items" to listOf(mapOf("flag" to "yes"))))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("a present-but-null 'forAll' body taints instead of silently counting as false") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not (vars.items->forAll(i | i.flag))")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("items" to listOf(mapOf("flag" to null))))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("a non-Boolean 'reject' body taints instead of silently keeping the element") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->reject(i | i.flag)->notEmpty()")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("items" to listOf(mapOf("flag" to "yes"))))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("a non-Boolean 'one' body taints instead of silently counting as non-matching") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->one(i | i.flag)")).parse()
            val env =
                mapOf(
                    "self" to order(),
                    "vars" to mapOf("items" to listOf(mapOf("flag" to true), mapOf("flag" to "yes"))),
                )
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("a non-Boolean 'exists' body taints instead of silently counting as false") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not (vars.items->exists(i | i.flag))")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("items" to listOf(mapOf("flag" to "yes"))))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("a genuinely Boolean 'forAll' body still returns an untainted true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->forAll(i | i.flag)")).parse()
            val env =
                mapOf(
                    "self" to order(),
                    "vars" to mapOf("items" to listOf(mapOf("flag" to true), mapOf("flag" to true))),
                )
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'exists' still short-circuits on the first genuinely-true body without tainting from later elements") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->exists(i | i.flag)")).parse()
            val env =
                mapOf(
                    "self" to order(),
                    "vars" to mapOf("items" to listOf(mapOf("flag" to true), mapOf<String, Any?>())),
                )
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'forAll' still short-circuits on the first genuinely-false body without tainting from later elements") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->forAll(i | i.flag)")).parse()
            val env =
                mapOf(
                    "self" to order(),
                    "vars" to mapOf("items" to listOf(mapOf("flag" to false), mapOf("flag" to true))),
                )
            evaluator.eval(expr = expr, env = env) shouldBe false
            evaluator.referencedMissingVariable shouldBe false
        }

        test("taint recorded by a non-Boolean iterator body survives a throwing sibling operand in 'or'") {
            // Exception-safety counterpart for the iterator-body fix: the taint is
            // recorded inside an `or` left operand that runs under evalIsolated
            // (which clears and later restores the shared flag), and the right
            // operand then throws. The taint must still reach the caller.
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.rows->forAll(r | r.flag) or num.foo")).parse()
            val env =
                mapOf(
                    "self" to order(),
                    "vars" to mapOf("rows" to listOf(mapOf("flag" to "yes"))),
                    "num" to 5,
                )
            shouldThrow<OclEvaluationException> { evaluator.eval(expr = expr, env = env) }
            evaluator.referencedMissingVariable shouldBe true
        }

        // ── (3) Collection-receiver narrowing. `->op()` on a present, non-null, ─
        //    non-List receiver silently became an *empty* collection, so
        //    `forAll` returned a vacuous, untainted `true`, `isEmpty()` an
        //    untainted `true`, and `excludes(x)` an untainted `true` — all
        //    trustworthy-looking answers derived from a swallowed type error.
        //    (A genuinely `null` receiver keeps its existing "empty collection"
        //    reading and is deliberately left untainted; see the KDoc on
        //    OclEvaluator.asCollection.)

        test("'forAll' over a present-but-non-collection receiver taints instead of returning a vacuous true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.name->forAll(i | i.flag)")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("name" to "Alice"))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'isEmpty' over a present-but-non-collection receiver taints instead of returning an untainted true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.name->isEmpty()")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("name" to "Alice"))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'excludes' over a present-but-non-collection receiver taints instead of returning an untainted true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.count->excludes(1)")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("count" to 7))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("taint recorded by a non-collection receiver survives a throwing sibling operand in 'or'") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.name->notEmpty() or num.foo")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("name" to "Alice"), "num" to 5)
            shouldThrow<OclEvaluationException> { evaluator.eval(expr = expr, env = env) }
            evaluator.referencedMissingVariable shouldBe true
        }

        test("a genuinely empty collection receiver still yields an untainted vacuous forAll true") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->forAll(i | i.flag)")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("items" to emptyList<Any?>()))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("closure over a non-collection receiver keeps its documented implicit-singleton reading, untainted") {
            val evaluator = OclEvaluator(self = order("id", "name"))
            val expr = OclParser(tokens = OclLexer.tokenize("self->closure(c | c.attributes)->size()")).parse()
            evaluator.eval(expr = expr, env = mapOf("self" to order("id", "name"))) shouldBe 2
            evaluator.referencedMissingVariable shouldBe false
        }

        // ── (4) iterate() receiver narrowing — same `as? List<*> ?: emptyList()` ─
        //    shape: a non-collection receiver silently produced zero iterations,
        //    returning the accumulator's initial value untouched and untainted.

        test("'iterate' over a present-but-non-collection receiver taints instead of returning the untouched accumulator") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.name->iterate(i; acc = true | acc and i.flag)")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("name" to "Alice"))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'iterate' over a real collection still accumulates untainted") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->iterate(i; acc = 0 | acc + 1)")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("items" to listOf(1, 2, 3)))
            evaluator.eval(expr = expr, env = env) shouldBe 3
            evaluator.referencedMissingVariable shouldBe false
        }

        // ── (5) union()/intersection() argument narrowing — same shape again, on ─
        //    the *argument* instead of the receiver.

        test("'union' with a present-but-non-collection argument taints instead of silently dropping it") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->union(vars.name)->isEmpty()")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("items" to emptyList<Any?>(), "name" to "Alice"))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'intersection' with a present-but-non-collection argument taints instead of silently yielding empty") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->intersection(vars.name)->isEmpty()")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("items" to listOf(1), "name" to "Alice"))
            evaluator.eval(expr = expr, env = env) shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'union' with two real collections stays untainted") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.a->union(vars.b)->size()")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("a" to listOf(1), "b" to listOf(2, 3)))
            evaluator.eval(expr = expr, env = env) shouldBe 3
            evaluator.referencedMissingVariable shouldBe false
        }

        // ── (6) Constructs audited and found ALREADY safe — regression anchors ───
        //    so a later refactor cannot silently reintroduce the defect here.
        //    'not' and 'if' both narrow with `as? Boolean ?: throw`, i.e. they
        //    surface a non-Boolean as an OclEvaluationException (fail-closed)
        //    instead of coercing it, and neither isolates the taint flag, so
        //    taint accumulated by the operand/condition always survives.

        test("'not' rejects a non-Boolean operand instead of coercing it") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not flag")).parse()
            shouldThrow<OclEvaluationException> {
                evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes"))
            }
        }

        test("'not' preserves taint accumulated by its operand") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("not (vars.missing <> 1)")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to emptyMap<String, Any?>()))
            value shouldBe false
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'if' rejects a non-Boolean condition instead of coercing it to false") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("if flag then false else true endif")).parse()
            shouldThrow<OclEvaluationException> {
                evaluator.eval(expr = expr, env = mapOf("self" to order(), "flag" to "yes"))
            }
        }

        test("'if' preserves taint accumulated by its condition") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("if vars.missing <> 1 then true else false endif")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to emptyMap<String, Any?>()))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        test("'if' only evaluates the taken branch, so an untaken branch's missing variable does not taint") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("if a then true else missingx endif")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "a" to true))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'let' preserves taint accumulated by its initializer") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("let x = (vars.missing <> 1) in x")).parse()
            val value = evaluator.eval(expr = expr, env = mapOf("self" to order(), "vars" to emptyMap<String, Any?>()))
            value shouldBe true
            evaluator.referencedMissingVariable shouldBe true
        }

        // ══ 'closure' body resolves the enclosing env, instead of a fresh ════════
        //    `mapOf(bindingVar to item, "self" to self)` that dropped every other
        //    outer binding on the floor (branch fix/activity-guard-negation,
        //    A-3). The dropped-binding case was already fail-closed/over-
        //    tainting rather than a security regression (a missing `vars`
        //    reference taints and the resulting exception is read as "no
        //    successor" by evalClosure's catch) — this is a correctness fix,
        //    not a fail-open closure, but it does change closure *values*
        //    (previously-pruned branches can now be reached), so it is kept in
        //    its own commit separate from the wertneutral and/or/implies fix.

        test("'closure' body resolves outer env bindings instead of tainting them away") {
            val evaluator = OclEvaluator(self = order())
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->closure(i | vars.next)")).parse()
            val env = mapOf("self" to order(), "vars" to mapOf("items" to listOf(1), "next" to listOf(2)))
            val value = evaluator.eval(expr = expr, env = env)
            value shouldBe listOf(2)
            evaluator.referencedMissingVariable shouldBe false
        }

        test("'closure' body resolves the env-bound self, not the constructor self") {
            val ctorSelf = order("a")
            val envSelf = order("a", "b", "c")
            val evaluator = OclEvaluator(self = ctorSelf)
            val expr = OclParser(tokens = OclLexer.tokenize("vars.items->closure(i | self.attributes)->size()")).parse()
            val env = mapOf("self" to envSelf, "vars" to mapOf("items" to listOf(1)))
            val value = evaluator.eval(expr = expr, env = env)
            value shouldBe 3
        }
    })
