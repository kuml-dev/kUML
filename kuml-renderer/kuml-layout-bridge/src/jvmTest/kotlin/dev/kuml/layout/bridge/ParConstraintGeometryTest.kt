package dev.kuml.layout.bridge

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlin.system.measureTimeMillis

/** PAL wave 2C-b3: pin grid, expression wrap and parameter lines of a constraint box (one geometry source). */
class ParConstraintGeometryTest :
    StringSpec({
        fun constraint(
            expression: String,
            params: Int = 0,
        ) = ConstraintDefinition(
            id = "C",
            name = "C",
            expression = expression,
            parameters =
                (0 until params).map {
                    ConstraintParameter(
                        name = "p$it",
                        typeId = "T",
                        direction = if (it % 2 == 0) ConstraintParameterDirection.In else ConstraintParameterDirection.Out,
                    )
                },
        )

        fun squash(s: String) = s.filterNot { it.isWhitespace() }

        "four pins: squares do not intersect and are at least 8 px apart vertically" {
            val c = constraint(expression = "a = b", params = 4)
            val bounds = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 200f, height = ParConstraintGeometry.contentHeight(c)))
            val ys = (0 until 4).map { ParConstraintGeometry.pinPoint(constraint = c, index = it, bounds = bounds).y }
            for (i in 0 until 3) {
                (ys[i + 1] - ys[i] - ParConstraintGeometry.PIN_SIZE) shouldBeGreaterThanOrEqual 8f
            }
            (ys[1] - ys[0]) shouldBe 18f
        }

        "wrap: exactly 60 characters stay one line" {
            ParConstraintGeometry.wrapExpression("x".repeat(60)).size shouldBe 1
        }

        "wrap: 120 characters with && break before the operator into two lines" {
            val expr = "a".repeat(54) + " > 0 && " + "b".repeat(51) + " < 1"
            expr.length shouldBe 117
            val lines = ParConstraintGeometry.wrapExpression(expr)
            lines.size shouldBe 2
            lines[1] shouldStartWith "&&"
            lines.forEach { it.length shouldBeLessThanOrEqual 60 }
        }

        "wrap: a 130 character token without blanks is cut hard 60/60/10" {
            ParConstraintGeometry.wrapExpression("y".repeat(130)).map { it.length } shouldBe listOf(60, 60, 10)
        }

        "wrap: an empty or blank expression has no lines" {
            ParConstraintGeometry.wrapExpression("").shouldBeEmpty()
            ParConstraintGeometry.wrapExpression("   ").shouldBeEmpty()
        }

        "wrap: nothing is lost, only blanks" {
            val expr = (1..30).joinToString(separator = " + ") { "term$it" }
            val lines = ParConstraintGeometry.wrapExpression(expr)
            squash(lines.joinToString("")) shouldBe squash(expr)
            lines.forEach { it.length shouldBeLessThanOrEqual 60 }
        }

        "wrap: operator preference beats a later blank (+ before space)" {
            val expr = "a".repeat(40) + " + " + "b".repeat(30)
            val lines = ParConstraintGeometry.wrapExpression(expr)
            lines.size shouldBe 2
            lines[1] shouldStartWith "+"
        }

        "wrap: 10 000 characters are capped at the text limit and wrapped fast" {
            val c = constraint(expression = "z ".repeat(5_000))
            var lines: List<String> = emptyList()
            val ms = measureTimeMillis { lines = ParConstraintGeometry.expressionLinesOf(c) }
            (ms <= 50L) shouldBe true
            lines.sumOf { it.length } shouldBeLessThanOrEqual Sysml2Limits.TEXT
            lines.last() shouldEndWith "…"
        }

        "first pin lies below the divider by at least 4 px for 1 to 4 expression lines" {
            for (n in 1..4) {
                (
                    ParConstraintGeometry.pinY(
                        expressionLines = n,
                        index = 0,
                    ) - ParConstraintGeometry.dividerAfterExpressionY(n)
                ) shouldBeGreaterThanOrEqual
                    4f
            }
            (ParConstraintGeometry.pinY(expressionLines = 0, index = 0) - ParConstraintGeometry.FIRST_DIVIDER_Y) shouldBeGreaterThanOrEqual
                4f
        }

        "size provider height equals contentHeight and the box holds the last pin" {
            val model =
                sysml2Model(name = "S") {
                    constraintDef(
                        name = "Three",
                        expression = "w ".repeat(40),
                        parameters =
                            listOf(
                                ConstraintParameter(name = "a", direction = ConstraintParameterDirection.In),
                                ConstraintParameter(name = "b", direction = ConstraintParameterDirection.In),
                                ConstraintParameter(name = "c", direction = ConstraintParameterDirection.Out),
                            ),
                    )
                }
            val c = model.definitions.filterIsInstance<ConstraintDefinition>().single()
            val size = Sysml2LayoutBridge.parContentAwareSizeProvider(model).sizeOf(elementId = c.id, elementKind = "ConstraintDefinition")
            size.height shouldBe ParConstraintGeometry.contentHeight(c)
            val lastPin = ParConstraintGeometry.pinY(expressionLines = ParConstraintGeometry.expressionLinesOf(c).size, index = 2)
            (size.height - lastPin) shouldBeGreaterThanOrEqual ParConstraintGeometry.PIN_SIZE / 2f
        }

        "parameter line: unit shown, absent without unit, overlong type keeps the unit" {
            val p = ConstraintParameter(name = "pMix", typeId = "Druck", direction = ConstraintParameterDirection.In)
            ParConstraintGeometry.parameterLine(p = p, unitOf = { "Pa" }) shouldBe "«in» pMix : Druck [Pa]"
            ParConstraintGeometry.parameterLine(p = p, unitOf = { null }) shouldBe "«in» pMix : Druck"
            val long = p.copy(typeId = "T".repeat(500))
            val line = ParConstraintGeometry.parameterLine(p = long, unitOf = { "Pa" })
            line shouldEndWith " [Pa]"
            (line.length <= 200 + 20) shouldBe true
        }

        "unit lookup reads AttributeDefinition.unit" {
            val model =
                sysml2Model(name = "U") {
                    attributeDef(name = "Druck", unitSymbol = "Pa")
                }
            val def = model.definitions.filterIsInstance<AttributeDefinition>().single()
            ParConstraintGeometry.unitLookup(model)(def.id) shouldBe "Pa"
        }
    })
