package dev.kuml.transform.umlerm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCheckConstraint
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.RelationshipKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Regression coverage for the Kotlin-script-injection finding: a bare `$` in
 * any user-controlled field previously survived [ErmScriptRenderer.escape]
 * untouched, so a value like `${'$'}{Runtime.getRuntime().exec("...")}`
 * would become a *live* Kotlin string-template expression in the generated
 * `.erm.kuml.kts` script — executed the moment `kuml render` compiles it.
 *
 * [ErmScriptRenderer.escape] must neutralise every `$` to the literal
 * `${'$'}` idiom so it can never re-open a template in the emitted script,
 * regardless of which field carried it.
 */
class ErmScriptRendererEscapeTest :
    FunSpec({

        val payload = "\$injected"
        val neutralized = "\${'\$'}injected"

        fun modelWith(
            modelName: String = "Safe",
            attrDefault: String? = null,
            customType: String? = null,
            checkExpression: String? = null,
            relName: String? = null,
            sourceRole: String? = null,
            targetRole: String? = null,
        ): ErmModel {
            val customerId = "customer"
            val orderId = "order"
            val attrs =
                listOf(
                    ErmAttribute(id = "id", name = "id", type = ErmDataType.Uuid, primaryKey = true),
                    ErmAttribute(
                        id = "amount",
                        name = "amount",
                        type = customType?.let { ErmDataType.Custom(it) } ?: ErmDataType.Integer(),
                        default = attrDefault,
                    ),
                )
            val checks =
                checkExpression
                    ?.let { listOf(ErmCheckConstraint(id = "chk", name = "chk", expression = it)) }
                    .orEmpty()
            return ErmModel(
                name = modelName,
                entities =
                    listOf(
                        ErmEntity(id = customerId, name = "customer"),
                        ErmEntity(id = orderId, name = "order", attributes = attrs, checks = checks),
                    ),
                relationships =
                    listOf(
                        ErmRelationship(
                            id = "rel",
                            name = relName,
                            sourceEntityId = customerId,
                            targetEntityId = orderId,
                            sourceCardinality = Cardinality.ONE,
                            targetCardinality = Cardinality.ZERO_MANY,
                            kind = RelationshipKind.NON_IDENTIFYING,
                            sourceRole = sourceRole,
                            targetRole = targetRole,
                        ),
                    ),
            )
        }

        test("a '$' in the model/diagram name is neutralized inside every string literal") {
            // The leading "// Source model: ..." line is a plain Kotlin *comment*,
            // not a string literal, so it legitimately still carries the raw name —
            // a bare "$" there can never re-open a template. The two string-literal
            // occurrences (`ermModel(name = "...")` and `diagram(name = "...")`)
            // are the ones that matter, and both must be neutralized.
            val script = ErmScriptRenderer.render(modelWith(modelName = payload))
            script shouldContain """ermModel(name = "$neutralized")"""
            script shouldContain """diagram(name = "$neutralized""""
        }

        test("a newline/CR in the model name cannot terminate the header comment early") {
            // Unlike a bare "$", a raw '\n' or '\r' in the "// Source model: ..."
            // comment line *can* be dangerous: it terminates the `//` comment and
            // lets the remainder of the (attacker-controlled) name execute as live
            // top-level Kotlin the moment the generated script is compiled.
            val injected = "x\nRuntime.getRuntime().exec(\"evil\")\n//"
            val script = ErmScriptRenderer.render(modelWith(modelName = injected))
            val headerLine = script.lineSequence().first { it.startsWith("// Source model:") }
            headerLine shouldContain "x"
            headerLine shouldNotContain "\n"
            script shouldNotContain "Runtime.getRuntime().exec(\"evil\")\n"
        }

        test("a '$' in an attribute default value is neutralized") {
            val script = ErmScriptRenderer.render(modelWith(attrDefault = payload))
            script shouldContain neutralized
            script shouldNotContain payload
        }

        test("a '$' in a custom SQL type override is neutralized") {
            val script = ErmScriptRenderer.render(modelWith(customType = payload))
            script shouldContain neutralized
            script shouldNotContain payload
        }

        test("a '$' in a check-constraint expression is neutralized") {
            val script = ErmScriptRenderer.render(modelWith(checkExpression = payload))
            script shouldContain neutralized
            script shouldNotContain payload
        }

        test("a '$' in a relationship name is neutralized") {
            val script = ErmScriptRenderer.render(modelWith(relName = payload))
            script shouldContain neutralized
            script shouldNotContain payload
        }

        test("a '$' in source/target roles is neutralized") {
            val script = ErmScriptRenderer.render(modelWith(sourceRole = payload, targetRole = payload))
            script shouldContain neutralized
            script shouldNotContain payload
        }
    })
