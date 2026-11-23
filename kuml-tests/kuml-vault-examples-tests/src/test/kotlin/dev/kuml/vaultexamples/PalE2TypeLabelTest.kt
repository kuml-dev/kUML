@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.c4.model.c4TypeTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * PAL wave E2 (16a): the bracketed C4 type tag can be overridden with `typeLabel` on persons and software systems.
 * Everything is measured on the SVG of the full pipeline (no pixels, no byte comparison).
 */
class PalE2TypeLabelTest :
    FunSpec({
        fun render(script: String): String {
            val r = VaultExampleRenderer.render(script = script)
            r.error shouldBe null
            return r.svg!!
        }

        fun sys(
            external: Boolean = false,
            label: String? = null,
        ) = C4SoftwareSystem(id = "s", name = "Doc", external = external, typeLabel = label)

        test("c4TypeTag: custom label replaces the derived tag, null/blank keep it, external gets no prefix") {
            c4TypeTag(sys(label = "Dokument")) shouldBe "[Dokument]"
            c4TypeTag(sys(external = true, label = "Dokument")) shouldBe "[Dokument]"
            c4TypeTag(sys(label = null)) shouldBe "[Software System]"
            c4TypeTag(sys(external = true, label = null)) shouldBe "[External Software System]"
            c4TypeTag(sys(label = "")) shouldBe "[Software System]"
            c4TypeTag(sys(external = true, label = "   ")) shouldBe "[External Software System]"
            c4TypeTag(sys(label = "  Dokument ")) shouldBe "[Dokument]"
            c4TypeTag(C4Person(id = "p", name = "P", typeLabel = "Rolle")) shouldBe "[Rolle]"
            c4TypeTag(C4Person(id = "p", name = "P")) shouldBe "[Person]"
            c4TypeTag(C4Person(id = "p", name = "P", external = true)) shouldBe "[External Person]"
        }

        test("c4TypeTag: label is capped at 80 characters") {
            val tag = c4TypeTag(sys(label = "x".repeat(500)))
            tag shouldBe "[" + "x".repeat(80) + "]"
        }

        test("SVG: custom type label is drawn, the box is at least as wide as the tag text, the derived tag is gone") {
            val label = "Referenzdokument zur Fliessmatrix und Schnittstellenbeschreibung (Dokument)"
            val svg =
                render(
                    """
                    c4Model(name = "Tag") {
                        val a = softwareSystem(name = "Own")
                        val d = softwareSystem(name = "Doc") { external = true; typeLabel = "$label" }
                        val p = person(name = "Rolle") { typeLabel = "Bediener" }
                        relationship(source = a, target = d) { description = "uses" }
                        relationship(source = p, target = a) { description = "uses" }
                        systemContextDiagram(name = "Tag") { include(a, d, p) }
                    }
                    """.trimIndent(),
                )
            val doc = SvgGeometry.nodes(svg).single { Regex(">\\s*Doc\\s*<").containsMatchIn(it.body) }
            doc.body shouldContain "[$label]"
            doc.body shouldNotContain "External Software System"
            val w = Regex("""<rect[^>]*\swidth="([-0-9.]+)"""").find(doc.body)!!.groupValues[1].toDouble()
            // conservative lower bound of the proportional text width (4.5 px per character)
            (w >= (label.length + 2) * 4.5) shouldBe true
            val person = SvgGeometry.nodes(svg).single { Regex(">\\s*Rolle\\s*<").containsMatchIn(it.body) }
            person.body shouldContain "[Bediener]"
        }

        test("SVG: markup characters in the type label are escaped exactly once") {
            val svg =
                render(
                    """
                    c4Model(name = "Esc") {
                        val a = softwareSystem(name = "Own") { typeLabel = "A<B & \"C\"" }
                        systemContextDiagram(name = "Esc") { include(a) }
                    }
                    """.trimIndent(),
                )
            svg shouldContain "[A&lt;B &amp; &quot;C&quot;]"
            svg shouldNotContain "&amp;amp;"
            svg shouldNotContain "&amp;lt;"
        }
    })
