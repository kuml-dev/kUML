@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.GroupId
import dev.kuml.layout.Insets
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2PackageDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Wave 2I-a - the layout graph of package diagrams: geometry and structure only, no pixels, no glyph widths. */
class Sysml2Wave2IaBridgeTest :
    FunSpec({
        fun graphOf(m: Sysml2Model) = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = m.diagrams.single() as Sysml2PackageDiagram)

        fun planOf(m: Sysml2Model) = Sysml2PackageLayout.plan(model = m, diagram = m.diagrams.single() as Sysml2PackageDiagram)

        val nested =
            sysml2Model("N") {
                pkg("A") {
                    partDef("X", id = "A::X")
                    pkg("B") {
                        partDef("Y", id = "A::B::Y")
                        pkg("C") {
                            partDef("Z", id = "A::B::C::Z")
                            pkg("D") {
                                partDef("W", id = "A::B::C::D::W")
                                pkg("E") { partDef("V", id = "A::B::C::D::E::V") }
                            }
                        }
                    }
                }
                packageDiagram("N") { includeById("pkg:A") }
            }

        test("packages become compound groups with the frame padding and minimum size; parents follow the package tree") {
            val g = graphOf(nested)
            g.groups.map { it.id.value } shouldContainExactly listOf("pkg:A", "pkg:A::B", "pkg:A::B::C")
            g.groups.map { it.parent?.value } shouldContainExactly listOf(null, "pkg:A", "pkg:A::B")
            g.groups.forEach {
                it.padding shouldBe Insets(top = 36f, right = 16f, bottom = 16f, left = 16f)
                it.minSize shouldBe Size(width = 140f, height = 60f)
                it.layoutAsCompound shouldBe true
            }
        }

        test("from depth 4 a package is a flat collapsed card in the level-3 frame; the tab reads C::D and C::D::E") {
            val plan = planOf(nested)
            val collapsed = plan.cards.filter { it.card.kind == PkgCardKind.CollapsedPackage }
            collapsed.map { it.card.id } shouldContainExactly listOf("pkg:A::B::C::D", "pkg:A::B::C::D::E")
            collapsed.map { it.card.title } shouldContainExactly listOf("C::D", "C::D::E")
            collapsed.forEach { it.groupId shouldBe "pkg:A::B::C" }
            collapsed[0]
                .card.sections
                .single()
                .lines
                .map { it.text } shouldContainExactly listOf("W")
            collapsed[1]
                .card.sections
                .single()
                .lines
                .map { it.text } shouldContainExactly listOf("V")
            // members of collapsed packages are not cards of their own
            plan.cards.map { it.card.id }.contains("A::B::C::D::W") shouldBe false
            graphOf(nested).groups.size shouldBe 3
        }

        test("definition cards sit in their package frame, with the keyword of the definition kind") {
            val plan = planOf(nested)
            val x = plan.cards.single { it.card.id == "A::X" }
            x.groupId shouldBe "pkg:A"
            x.card.keyword shouldBe "«part def»"
            x.card.kind shouldBe PkgCardKind.Definition
            (x.card.width in 120f..280f) shouldBe true
            x.card.height shouldBe 44f
        }

        test("a collapsed card lists at most 8 members and then +N more") {
            val m =
                sysml2Model("M") {
                    pkg("A") { pkg("B") { pkg("C") { pkg("D") { repeat(11) { partDef("M$it", id = "m$it") } } } } }
                    packageDiagram("D") { includeById("pkg:A") }
                }
            val lines =
                planOf(m)
                    .cards
                    .single { it.card.kind == PkgCardKind.CollapsedPackage }
                    .card.sections
                    .single()
                    .lines
            lines.size shouldBe 9
            lines.last().text shouldBe "+3 more"
        }

        val withImports =
            sysml2Model("I") {
                pkg("Common") { attributeDef("Mass", id = "Common::Mass") }
                pkg("App") {
                    imports("Common::*")
                    imports("Common::Mass", public = true)
                    imports("Nowhere::*")
                    imports("App::Inner::*")
                    partDef("Main", id = "App::Main")
                    pkg("Inner") { partDef("Helper", id = "App::Inner::Helper") }
                }
                packageDiagram("I") {
                    includeById("pkg:App")
                    includeById("pkg:Common")
                }
            }

        test("imports: namespace to the package frame, member to the card; unknown and descendant targets are text lines, no edge") {
            val plan = planOf(withImports)
            // sorted by (source, target, id): the card id "Common::Mass" sorts before the frame id "pkg:Common"
            plan.edges.map { it.id } shouldContainExactly
                listOf("import:pkg:App::Common::Mass", "import:pkg:App::Common::*")
            plan.edges.map { it.source } shouldContainExactly listOf("pkg:App", "pkg:App")
            plan.edges.map { it.target } shouldContainExactly listOf("Common::Mass", "pkg:Common")
            plan.frames.single { it.id == "pkg:App" }.textLines shouldContainExactly
                listOf("import Nowhere::*", "import App::Inner::*")
        }

        test("the frame padding on top grows with the number of text lines") {
            val g = graphOf(withImports)
            g.groups
                .single { it.id == GroupId("pkg:App") }
                .padding.top shouldBe 36f + 14f * 2
            g.groups
                .single { it.id == GroupId("pkg:Common") }
                .padding.top shouldBe 36f
        }

        test("an import of a package that is not drawn is a text line") {
            val m =
                sysml2Model("M") {
                    pkg("Common") { partDef("C", id = "C") }
                    pkg("App") { imports("Common::*") }
                    packageDiagram("D") { includeById("pkg:App") }
                }
            val plan = planOf(m)
            plan.edges.shouldBeEmpty()
            plan.frames.single().textLines shouldContainExactly listOf("import Common::*")
        }

        test("an import of a member in a collapsed package points at the collapsed card") {
            val m =
                sysml2Model("M") {
                    pkg("Lib") { partDef("L", id = "L") }
                    pkg("A") {
                        imports("Lib::*")
                        pkg("B") { pkg("C") { pkg("D") { partDef("Deep", id = "Deep") } } }
                    }
                    pkg("User") { imports("A::B::C::D::Deep") }
                    packageDiagram("D") {
                        includeById("pkg:A")
                        includeById("pkg:User")
                        includeById("pkg:Lib")
                    }
                }
            val plan = planOf(m)
            val e = plan.edges.single { it.source == "pkg:User" }
            e.target shouldBe "pkg:A::B::C::D"
        }

        test("a definition included on its own is a root-level card; a definition of a drawn package is not duplicated") {
            val m =
                sysml2Model("M") {
                    pkg("A") { partDef("X", id = "A::X") }
                    val lone = partDef("Lone", id = "Lone")
                    packageDiagram("D") {
                        includeById("A::X")
                        include(lone)
                        includeById("pkg:A")
                    }
                }
            val plan = planOf(m)
            plan.cards.map { it.card.id to it.groupId } shouldContainExactly listOf("Lone" to null, "A::X" to "pkg:A")
        }

        test("views and viewpoints are root cards with expose / filter / stakeholders / concerns; empty fields are left out") {
            val m =
                sysml2Model("M") {
                    val vp = viewpoint("Safety") { stakeholder("Engineer") }
                    val v =
                        view("Overview") {
                            satisfies(vp)
                            expose("A::*")
                        }
                    packageDiagram("D") {
                        include(v)
                        include(vp)
                    }
                }
            val plan = planOf(m)
            val view = plan.cards.single { it.card.kind == PkgCardKind.View }.card
            view.keyword shouldBe "«view»"
            view.sections.map { it.heading } shouldContainExactly listOf("expose")
            val vpCard = plan.cards.single { it.card.kind == PkgCardKind.Viewpoint }.card
            vpCard.sections.map { it.heading } shouldContainExactly listOf("stakeholders")
            plan.edges.map { it.id } shouldContainExactly listOf("satisfy:view:Overview::viewpoint:Safety")
            (view.height > 44f) shouldBe true
        }

        test("a satisfy edge needs both cards: a viewpoint that is not drawn gives no edge") {
            val m =
                sysml2Model("M") {
                    viewpoint("S")
                    val v = view("V") { satisfies("S") }
                    packageDiagram("D") { include(v) }
                }
            planOf(m).edges.shouldBeEmpty()
        }

        test("the graph is deterministic: same input, equal graph; nodes follow elementIds, edges are sorted by (source, target)") {
            val a = graphOf(withImports)
            val b = graphOf(withImports)
            a shouldBe b
            a.nodes.map { it.id.value } shouldContainExactly listOf("App::Main", "App::Inner::Helper", "Common::Mass")
            a.edges.map { it.source.nodeId.value to it.target.nodeId.value } shouldContainExactly
                listOf("pkg:App" to "Common::Mass", "pkg:App" to "pkg:Common")
        }

        test("hostile model: a package cycle and unknown parents do not hang the plan") {
            val cyc =
                Sysml2Model(
                    name = "C",
                    packages =
                        listOf(
                            dev.kuml.sysml2.Sysml2Package(id = "a", name = "A", parentId = "b"),
                            dev.kuml.sysml2.Sysml2Package(id = "b", name = "B", parentId = "a"),
                        ),
                    diagrams = listOf(Sysml2PackageDiagram(name = "D", elementIds = listOf("a", "b"))),
                )
            graphOf(cyc).nodes.shouldBeEmpty()
        }

        test("long names are clipped in the card, the width stays within 120..280") {
            val m =
                sysml2Model("M") {
                    val p = partDef("N".repeat(300), id = "long")
                    packageDiagram("D") { include(p) }
                }
            val card = planOf(m).cards.single().card
            card.width shouldBe 280f
            card.title.endsWith("…") shouldBe true
        }

        test("layout hints: the package diagram gets a wider layer gap") {
            val m = nested
            Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = m.diagrams.single()).spacing.layerToLayer shouldBe 80f
        }

        test("matrix: SYSML2_PKG is a layered art that honours every option") {
            SpecDiagramKinds.of(nested.diagrams.single()) shouldBe SpecDiagramKind.SYSML2_PKG
            LayoutEffectMatrix.effectiveFor(SpecDiagramKind.SYSML2_PKG) shouldBe LayoutEffectMatrix.effectiveFor(SpecDiagramKind.SYSML2_BDD)
        }

        test("node ids and group ids never collide") {
            val g = graphOf(nested)
            val nodeIds = g.nodes.map { it.id.value }.toSet()
            g.groups.map { it.id.value }.none { it in nodeIds } shouldBe true
            NodeId("x").value shouldContain "x"
        }
    })
