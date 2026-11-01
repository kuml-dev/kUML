package dev.kuml.sysml2.projection

import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.Sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class Sysml2IbdAttributesTest :
    FunSpec(body = {
        val mass = AttributeDefinition(id = "Mass", name = "Mass")
        val part = PartUsage(id = "p1", name = "p1", definitionId = "Def")

        fun model(vararg attrs: AttributeUsage) =
            Sysml2Model(
                name = "M",
                definitions = listOf(PartDefinition(id = "Def", name = "Def"), mass),
                usages = listOf<dev.kuml.sysml2.Sysml2Usage>(part) + attrs,
            )

        fun attr(
            id: String,
            name: String,
            value: String? = null,
        ) = AttributeUsage(id = id, name = name, definitionId = "Mass", defaultExpression = value)

        test("more than MAX_LINES attributes: only MAX_LINES are shown, the rest is counted in hiddenCount") {
            val attrs = (1..11).map { attr(id = "Def::a$it", name = "a$it") }
            val c = ibdPartAttributes(model = model(*attrs.toTypedArray()), usage = part)
            c.lines.size shouldBe IbdAttributeCompartment.MAX_LINES
            c.hiddenCount shouldBe 11 - IbdAttributeCompartment.MAX_LINES
            c.lines.first().name shouldBe "a1"
            c.isEmpty shouldBe false
        }

        test("exactly MAX_LINES attributes: nothing hidden") {
            val attrs = (1..IbdAttributeCompartment.MAX_LINES).map { attr(id = "Def::a$it", name = "a$it") }
            ibdPartAttributes(model = model(*attrs.toTypedArray()), usage = part).hiddenCount shouldBe 0
        }

        test("a redefinition without a value keeps the value of the definition's attribute") {
            val c =
                ibdPartAttributes(
                    model = model(attr(id = "Def::w", name = "w", value = "12"), attr(id = "p1::w", name = "w")),
                    usage = part,
                )
            c.lines.single().text shouldBe "w : Mass = 12"
        }

        test("a redefinition with a value overrides the definition's value, in place") {
            val c =
                ibdPartAttributes(
                    model =
                        model(
                            attr(id = "Def::w", name = "w", value = "12"),
                            attr(id = "Def::x", name = "x"),
                            attr(id = "p1::w", name = "w", value = "7"),
                        ),
                    usage = part,
                )
            c.lines.map { it.text } shouldBe listOf("w : Mass = 7", "x : Mass")
        }

        test("no attributes: the compartment is empty") {
            ibdPartAttributes(model = model(), usage = part).isEmpty shouldBe true
        }
    })
