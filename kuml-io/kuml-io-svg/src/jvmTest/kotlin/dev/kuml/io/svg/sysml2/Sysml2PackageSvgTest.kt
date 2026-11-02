@file:Suppress("RequireNamedArguments")

package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2PackageDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Wave 2I-a - structural SVG checks of the package diagram: dash, labels, unfilled frames, escaping, determinism. */
class Sysml2PackageSvgTest :
    FunSpec(body = {

        fun render(
            m: Sysml2Model,
            sample: String? = null,
        ): String {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            val engine = LayoutEngineRegistry.get("elk.layered")!!
            val d = m.diagrams.single() as Sysml2PackageDiagram
            val lr: LayoutResult =
                engine.layout(
                    graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d),
                    hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d),
                )
            val svg = KumlSvgRenderer.toSvg(model = m, diagram = d, layoutResult = lr, theme = PlainTheme())
            if (sample != null) SampleOutput.write(filename = "sysml2-package/$sample.svg", content = svg)
            return svg
        }

        val model =
            sysml2Model("M") {
                pkg("Common") { attributeDef("Mass", id = "Common::Mass") }
                pkg("App") {
                    imports("Common::*")
                    imports("Common::Mass", public = true)
                    imports("Common::**")
                    imports("Nowhere::*")
                    partDef("Main", id = "App::Main")
                }
                val vp = viewpoint("Safety") { stakeholder("Eng") }
                val v =
                    view("Overview") {
                        satisfies(vp)
                        expose("App::*")
                    }
                packageDiagram("P") {
                    includeById("pkg:App")
                    includeById("pkg:Common")
                    include(v)
                    include(vp)
                }
            }

        test("import and satisfy edges are dashed 6 4 and carry their stereotype labels") {
            val svg = render(model, "basic")
            svg shouldContain "stroke-dasharray=\"6 4\""
            for (label in listOf("«import» *", "«import» **", "public «import»", "«satisfy»")) svg shouldContain label
            svg shouldContain "data-edge-id=\"import:pkg:App::Common::*\""
            svg shouldContain "data-edge-id=\"satisfy:view:Overview::viewpoint:Safety\""
        }

        test("package frames are not filled; the tab is opaque and painted after the edges") {
            val svg = render(model)
            val frame = Regex("""<g id="pkg:App" class="kuml-package-frame">.*?</g>""", RegexOption.DOT_MATCHES_ALL).find(svg)!!.value
            frame shouldContain "style=\"fill:none\""
            val tab = Regex("""<g id="tab:pkg:App" class="kuml-package-tab">.*?</g>""", RegexOption.DOT_MATCHES_ALL).find(svg)!!
            tab.value shouldContain "fill-opacity=\"1\""
            (tab.range.first > svg.indexOf("data-edge-id=\"import:pkg:App::Common::*\"")) shouldBe true
        }

        test("an import that cannot be an arrow is an italic text line in its frame") {
            val svg = render(model)
            svg shouldContain "import Nowhere::*"
            svg shouldContain "kuml-package-import-line"
        }

        test("names and ids are escaped: no raw markup from a package, definition, view or viewpoint name") {
            val hostile = "<script>alert(1)</script> & \"q\""
            val m =
                sysml2Model("M") {
                    pkg(hostile) { partDef(hostile, id = "d1") }
                    val vp =
                        viewpoint(hostile) {
                            stakeholder(hostile)
                            concern("<b>x</b>")
                        }
                    val v =
                        view(hostile) {
                            satisfies(vp)
                            expose("<i>y</i>")
                        }
                    packageDiagram("P") {
                        includeById("pkg:$hostile")
                        include(v)
                        include(vp)
                    }
                }
            val svg = render(m, "hostile")
            svg shouldNotContain "<script>"
            svg shouldNotContain "<b>x</b>"
            svg shouldNotContain "<i>y</i>"
            svg shouldContain "&lt;script&gt;"
            // frame ids are escaped exactly once: the builder escapes attribute values itself
            // (the shared SysML 2 edge renderer escapes its group ids twice; that is safe and unchanged)
            svg shouldContain "id=\"pkg:&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;q&quot;\""
        }

        test("two runs give byte-identical SVG") {
            render(model) shouldBe render(model)
        }

        test("a collapsed package is drawn as a tab card with its members") {
            val m =
                sysml2Model("M") {
                    pkg("A") { pkg("B") { pkg("C") { pkg("D") { partDef("Deep", id = "deep") } } } }
                    packageDiagram("P") { includeById("pkg:A") }
                }
            val svg = render(m, "collapsed")
            svg shouldContain "id=\"pkg:A::B::C::D\""
            svg shouldContain "C::D"
            svg shouldContain "Deep"
        }

        test("view and viewpoint cards show their fields; empty fields are left out") {
            val svg = render(model)
            svg shouldContain "«view»"
            svg shouldContain "«viewpoint»"
            svg shouldContain "expose"
            svg shouldContain "stakeholders"
            svg shouldNotContain ">concerns<"
        }
    })
