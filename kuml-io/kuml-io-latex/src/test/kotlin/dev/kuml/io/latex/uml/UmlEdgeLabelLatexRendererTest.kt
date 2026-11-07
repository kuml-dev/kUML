package dev.kuml.io.latex.uml

import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.uml.UmlEdgeLatexRenderer.UmlLineEnd
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlCommunicationPath
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private fun stereo(name: String): AppliedStereotype =
    object : AppliedStereotype {
        override val profileNamespace: String = "test"
        override val stereotypeName: String = name
        override val tags: Map<String, TagValue> = emptyMap()
    }

private fun end(
    role: String? = null,
    m: Multiplicity = Multiplicity(),
) = UmlAssociationEnd(typeId = "X", role = role, multiplicity = m)

private fun draw(
    labels: UmlEdgeLabels,
    route: EdgeRoute,
    sourceEnd: UmlLineEnd = UmlLineEnd.NONE,
    targetEnd: UmlLineEnd = UmlLineEnd.NONE,
): String {
    val out = StringBuilder()
    UmlEdgeLabelLatexRenderer.render(
        labels = labels,
        route = route,
        options = LatexRenderOptions(),
        out = out,
        sourceEnd = sourceEnd,
        targetEnd = targetEnd,
    )
    return out.toString()
}

private fun direct(
    sx: Float,
    sy: Float,
    tx: Float,
    ty: Float,
) = EdgeRoute.Direct(source = Point(x = sx, y = sy), target = Point(x = tx, y = ty))

private fun lines(s: String) = s.lines().filter { it.isNotBlank() }

private val ROLE_AND_MULT = UmlEdgeLabels(sourceRole = "r", sourceMultiplicity = "m")
private val TARGET_ROLE_AND_MULT = UmlEdgeLabels(targetRole = "r", targetMultiplicity = "m")

private fun offset(
    end: UmlLineEnd,
    length: Float,
): Float = UmlEdgeLabelLatexRenderer.endLabelOffset(end = end, segmentLength = length)

class UmlEdgeLabelLatexRendererTest :
    StringSpec({
        // ── UmlEdgeLabels.of ────────────────────────────────────────────────
        "multiplicity labels follow the SVG rule" {
            UmlEdgeLabels.multiplicityLabel(Multiplicity(lower = 1, upper = null)) shouldBe "1..*"
            UmlEdgeLabels.multiplicityLabel(Multiplicity(lower = 0, upper = 1)) shouldBe "0..1"
            UmlEdgeLabels.multiplicityLabel(Multiplicity(lower = 0, upper = null)) shouldBe "0..*"
            UmlEdgeLabels.multiplicityLabel(Multiplicity(lower = 3, upper = 3)) shouldBe "3"
            UmlEdgeLabels.multiplicityLabel(Multiplicity(lower = 1, upper = 1)) shouldBe null
        }

        "association gives name, roles and multiplicities per end" {
            val l =
                UmlEdgeLabels.of(
                    UmlAssociation(
                        id = "a",
                        name = "owns",
                        ends =
                            listOf(
                                end(role = "src", m = Multiplicity(lower = 1, upper = null)),
                                end(role = "tgt", m = Multiplicity(lower = 0, upper = 1)),
                            ),
                    ),
                )
            l.name shouldBe "owns"
            l.sourceRole shouldBe "src"
            l.sourceMultiplicity shouldBe "1..*"
            l.targetRole shouldBe "tgt"
            l.targetMultiplicity shouldBe "0..1"
        }

        "blank strings count as absent and association with one end does not crash" {
            val l =
                UmlEdgeLabels.of(
                    UmlAssociation(id = "a", name = "  ", ends = listOf(end(role = " ", m = Multiplicity(lower = 0, upper = null)))),
                )
            l.name shouldBe null
            l.sourceRole shouldBe null
            l.sourceMultiplicity shouldBe "0..*"
            l.targetRole shouldBe null
            l.targetMultiplicity shouldBe null
        }

        "stereotypes keep applied before plain order and are distinct" {
            val l =
                UmlEdgeLabels.of(
                    UmlAssociation(
                        id = "a",
                        ends = listOf(end(), end()),
                        stereotypes = listOf("b", " ", "a"),
                        appliedStereotypes = listOf(stereo("a")),
                    ),
                )
            l.stereotypes shouldBe listOf("a", "b")
        }

        "other relationship kinds" {
            UmlEdgeLabels.of(UmlDependency(id = "d", clientId = "a", supplierId = "b", name = "<<use>>")).name shouldBe "<<use>>"
            UmlEdgeLabels.of(UmlInclude(id = "i", baseId = "a", additionId = "b")).stereotypes shouldBe listOf("include")
            UmlEdgeLabels.of(UmlExtend(id = "e", baseId = "a", extensionId = "b")).stereotypes shouldBe listOf("extend")
            UmlEdgeLabels
                .of(UmlGeneralization(id = "g", specificId = "a", generalId = "b", appliedStereotypes = listOf(stereo("s"))))
                .stereotypes shouldBe listOf("s")
            UmlEdgeLabels
                .of(UmlInterfaceRealization(id = "r", implementingId = "a", interfaceId = "b", appliedStereotypes = listOf(stereo("t"))))
                .stereotypes shouldBe listOf("t")
            UmlEdgeLabels.of(UmlConnector(id = "c", end1Id = "a", end2Id = "b", name = "wire")).name shouldBe "wire"
            UmlEdgeLabels.of(UmlCommunicationPath(id = "p", end1Id = "a", end2Id = "b", name = "net")).name shouldBe "net"
            UmlEdgeLabels.of(UmlCommentLink(id = "l", commentId = "c", annotatedElementId = "a")) shouldBe UmlEdgeLabels.NONE
            UmlEdgeLabels.of(null) shouldBe UmlEdgeLabels.NONE
        }

        // ── render: centre label ────────────────────────────────────────────
        "no labels give no output" {
            draw(labels = UmlEdgeLabels.NONE, route = direct(sx = 0f, sy = 0f, tx = 100f, ty = 0f)) shouldBe ""
        }

        "name only sits at the mid-point on a white backdrop with negated y" {
            val tex = draw(labels = UmlEdgeLabels(name = "owns"), route = direct(sx = 0f, sy = 10f, tx = 100f, ty = 10f))
            lines(tex).size shouldBe 1
            tex shouldContain "fill=white, inner sep=1.5pt"
            tex shouldContain "at (50.000pt, -10.000pt) {owns};"
        }

        "stereotype only" {
            draw(
                labels = UmlEdgeLabels(stereotypes = listOf("use")),
                route = direct(sx = 0f, sy = 0f, tx = 100f, ty = 0f),
            ) shouldContain "{\\guillemotleft{}use\\guillemotright{}};"
        }

        "stereotype and name are separated by exactly one line break, stereotype first" {
            val tex =
                draw(
                    labels = UmlEdgeLabels(stereotypes = listOf("a", "b"), name = "n"),
                    route = direct(sx = 0f, sy = 0f, tx = 100f, ty = 0f),
                )
            tex shouldContain "{\\guillemotleft{}a, b\\guillemotright{}\\\\n};"
            Regex("""\\\\""").findAll(tex.substringAfter("{\\guillemotleft")).count() shouldBe 1
            tex shouldContain "align=center"
        }

        // ── render: ends ────────────────────────────────────────────────────
        "all labels come in the fixed order and only the centre label has a backdrop" {
            val labels =
                UmlEdgeLabels(
                    name = "n",
                    sourceRole = "sr",
                    sourceMultiplicity = "1..*",
                    targetRole = "tr",
                    targetMultiplicity = "0..1",
                )
            val l = lines(draw(labels = labels, route = direct(sx = 0f, sy = 0f, tx = 200f, ty = 0f)))
            l.size shouldBe 5
            l[0] shouldContain "{n};"
            l[1] shouldContain "{sr};"
            l[2] shouldContain "{1..*};"
            l[3] shouldContain "{tr};"
            l[4] shouldContain "{0..1};"
            l.drop(1).forEach { it shouldNotContain "fill=" }
        }

        "horizontal segment: source end to the right, target end to the left" {
            val labels = UmlEdgeLabels(sourceRole = "r", sourceMultiplicity = "m", targetRole = "R", targetMultiplicity = "M")
            val l = lines(draw(labels = labels, route = direct(sx = 0f, sy = 0f, tx = 200f, ty = 0f)))
            l[0] shouldContain "anchor=south west"
            l[0] shouldContain "at (10.000pt, -0.000pt)"
            l[1] shouldContain "anchor=north west"
            l[2] shouldContain "anchor=south east"
            l[2] shouldContain "at (190.000pt, -0.000pt)"
            l[3] shouldContain "anchor=north east"
        }

        "leftward and vertical segments" {
            val left = lines(draw(labels = ROLE_AND_MULT, route = direct(sx = 200f, sy = 0f, tx = 0f, ty = 0f)))
            left[0] shouldContain "anchor=south east"
            left[1] shouldContain "anchor=north east"
            val down = lines(draw(labels = ROLE_AND_MULT, route = direct(sx = 0f, sy = 0f, tx = 0f, ty = 200f)))
            down[0] shouldContain "anchor=north west"
            down[0] shouldContain "at (0.000pt, -10.000pt)"
            down[1] shouldContain "anchor=north east"
            val up = lines(draw(labels = ROLE_AND_MULT, route = direct(sx = 0f, sy = 200f, tx = 0f, ty = 0f)))
            up[0] shouldContain "anchor=south west"
            up[1] shouldContain "anchor=south east"
            val target = UmlEdgeLabels(targetRole = "r", targetMultiplicity = "m")
            val downTarget = lines(draw(labels = target, route = direct(sx = 0f, sy = 0f, tx = 0f, ty = 200f)))
            downTarget[0] shouldContain "anchor=south west"
            downTarget[1] shouldContain "anchor=south east"
        }

        "diagonal counts as horizontal" {
            draw(labels = UmlEdgeLabels(sourceRole = "r"), route = direct(sx = 0f, sy = 0f, tx = 100f, ty = 100f)) shouldContain
                "anchor=south west"
        }

        "short segment shrinks the offset to 40 percent" {
            draw(labels = UmlEdgeLabels(sourceRole = "r"), route = direct(sx = 0f, sy = 0f, tx = 15f, ty = 0f)) shouldContain
                "at (6.000pt, -0.000pt)"
        }

        "polyline end labels sit on the first and last segment, not on the chord" {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 0f, y = 0f),
                    target = Point(x = 100f, y = 100f),
                    waypoints = listOf(Point(x = 0f, y = 100f)),
                    cornerRadiusPx = 4f,
                )
            val l = lines(draw(labels = UmlEdgeLabels(sourceMultiplicity = "s", targetMultiplicity = "t"), route = route))
            l[0] shouldContain "at (0.000pt, -10.000pt)"
            l[1] shouldContain "at (90.000pt, -100.000pt)"
            val tree =
                EdgeRoute.TreeRounded(source = route.source, target = route.target, waypoints = route.waypoints, cornerRadiusPx = 4f)
            lines(draw(labels = UmlEdgeLabels(sourceMultiplicity = "s"), route = tree))[0] shouldContain "at (0.000pt, -10.000pt)"
        }

        "bezier uses the control points and tolerates zero or one of them" {
            val src = Point(x = 0f, y = 0f)
            val tgt = Point(x = 100f, y = 0f)
            val b2 = EdgeRoute.Bezier(source = src, target = tgt, controlPoints = listOf(Point(x = 0f, y = 50f), Point(x = 100f, y = 50f)))
            draw(labels = UmlEdgeLabels(sourceRole = "r"), route = b2) shouldContain "at (0.000pt, -10.000pt)"
            val b1 = EdgeRoute.Bezier(source = src, target = tgt, controlPoints = listOf(Point(x = 50f, y = 0f)))
            draw(labels = UmlEdgeLabels(targetRole = "r"), route = b1) shouldContain "at (90.000pt, -0.000pt)"
            val b0 = EdgeRoute.Bezier(source = src, target = tgt, controlPoints = emptyList())
            draw(labels = UmlEdgeLabels(sourceRole = "r"), route = b0) shouldContain "at (10.000pt, -0.000pt)"
        }

        "waypoint equal to the endpoint falls back to the next segment" {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(x = 0f, y = 0f),
                    target = Point(x = 100f, y = 0f),
                    waypoints = listOf(Point(x = 0f, y = 0f)),
                    cornerRadiusPx = 4f,
                )
            draw(labels = UmlEdgeLabels(sourceRole = "r"), route = route) shouldContain "at (10.000pt, -0.000pt)"
        }

        "source equal to target draws no end labels but keeps the centre label" {
            val labels = UmlEdgeLabels(name = "n", sourceRole = "r", targetMultiplicity = "m")
            val tex = draw(labels = labels, route = direct(sx = 5f, sy = 5f, tx = 5f, ty = 5f))
            lines(tex).size shouldBe 1
            tex shouldContain "{n};"
        }

        // ── escaping ────────────────────────────────────────────────────────
        "label text is escaped exactly once" {
            val tex = draw(labels = UmlEdgeLabels(name = "a_b&c %#\$ {x} ^~"), route = direct(sx = 0f, sy = 0f, tx = 100f, ty = 0f))
            tex shouldContain "a\\_b\\&c \\%\\#\\$ \\{x\\} \\textasciicircum{}\\textasciitilde{}"
            tex shouldNotContain "\\\\_"
        }

        "latex commands in labels are neutralised" {
            val labels = UmlEdgeLabels(name = "\\input{/etc/passwd}", sourceRole = "}];\\x")
            val tex = draw(labels = labels, route = direct(sx = 0f, sy = 0f, tx = 100f, ty = 0f))
            tex shouldContain "\\textbackslash{}input\\{/etc/passwd\\}"
            tex shouldContain "\\}];\\textbackslash{}x"
            tex shouldNotContain "\\input{"
        }

        "line breaks and separators become spaces" {
            val tex = draw(labels = UmlEdgeLabels(name = "a\n\nb\r\nc\u2028d"), route = direct(sx = 0f, sy = 0f, tx = 100f, ty = 0f))
            tex shouldContain "{a  b  c d};"
            lines(tex).size shouldBe 1
        }

        "dependency name with angle brackets is escaped without guillemets, include is fixed" {
            val route = direct(sx = 0f, sy = 0f, tx = 100f, ty = 0f)
            val dep =
                draw(labels = UmlEdgeLabels.of(UmlDependency(id = "d", clientId = "a", supplierId = "b", name = "<<use>>")), route = route)
            dep shouldContain "{\\textless{}\\textless{}use\\textgreater{}\\textgreater{}};"
            dep shouldNotContain "guillemot"
            val inc = draw(labels = UmlEdgeLabels.of(UmlInclude(id = "i", baseId = "a", additionId = "b")), route = route)
            inc shouldContain "{\\guillemotleft{}include\\guillemotright{}};"
        }

        "non-finite coordinates format as zero" {
            draw(labels = UmlEdgeLabels(name = "n"), route = direct(sx = Float.NaN, sy = 0f, tx = 100f, ty = 0f)) shouldContain
                "at (0.000pt,"
        }

        // ── clearance of diamonds and open heads ────────────────────────────
        "end label offset follows the line end" {
            val e = UmlLineEnd.values().associateWith { offset(end = it, length = 200f) }
            e.getValue(UmlLineEnd.DIAMOND) shouldBe 19f
            e.getValue(UmlLineEnd.OPEN_HEAD) shouldBe 15f
            e.getValue(UmlLineEnd.NONE) shouldBe 10f
            e.getValue(UmlLineEnd.DIAMOND_AND_OPEN_HEAD) shouldBe 31f
        }
        "end label anchor point sits the offset away from the endpoint" {
            val route = direct(sx = 0f, sy = 0f, tx = 200f, ty = 0f)
            draw(labels = ROLE_AND_MULT, route = route, sourceEnd = UmlLineEnd.DIAMOND) shouldContain "at (19.000pt, -0.000pt)"
            draw(labels = ROLE_AND_MULT, route = route) shouldContain "at (10.000pt, -0.000pt)"
            draw(labels = TARGET_ROLE_AND_MULT, route = route, targetEnd = UmlLineEnd.OPEN_HEAD) shouldContain "at (185.000pt, -0.000pt)"
        }
        "short end segments cap the offset at 40 percent" {
            offset(end = UmlLineEnd.DIAMOND, length = 30f) shouldBe (12f plusOrMinus 0.001f)
            offset(end = UmlLineEnd.DIAMOND, length = 47.5f) shouldBe (19f plusOrMinus 0.001f)
            (offset(end = UmlLineEnd.DIAMOND, length = 47f) < 19f) shouldBe true
        }
        "role and multiplicity share the cleared anchor point at a diamond, on both axes and ends" {
            val diamond = UmlLineEnd.DIAMOND
            val h = draw(labels = ROLE_AND_MULT, route = direct(sx = 0f, sy = 0f, tx = 200f, ty = 0f), sourceEnd = diamond)
            h shouldContain "anchor=south west"
            h shouldContain "anchor=north west"
            lines(h).count { it.contains("at (19.000pt, -0.000pt)") } shouldBe 2
            val v = draw(labels = ROLE_AND_MULT, route = direct(sx = 0f, sy = 0f, tx = 0f, ty = 200f), sourceEnd = diamond)
            v shouldContain "anchor=north west"
            v shouldContain "anchor=north east"
            lines(v).count { it.contains("at (0.000pt, -19.000pt)") } shouldBe 2
            val t = draw(labels = TARGET_ROLE_AND_MULT, route = direct(sx = 0f, sy = 0f, tx = 200f, ty = 0f), targetEnd = diamond)
            lines(t).count { it.contains("at (181.000pt, -0.000pt)") } shouldBe 2
        }
        "special characters at a diamond end are escaped exactly once" {
            val tex =
                draw(
                    labels = UmlEdgeLabels(sourceRole = "a_b&c", sourceMultiplicity = "5%"),
                    route = direct(sx = 0f, sy = 0f, tx = 200f, ty = 0f),
                    sourceEnd = UmlLineEnd.DIAMOND,
                )
            tex shouldContain "{a\\_b\\&c};"
            tex shouldContain "{5\\%};"
        }
    })
