package dev.kuml.io.svg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class SvgBuilderTest :
    FunSpec({

        test("SvgBuilder produces valid well-formed XML") {
            val b = SvgBuilder(pretty = false)
            b.tag(
                "svg",
                mapOf("xmlns" to "http://www.w3.org/2000/svg", "width" to "100", "height" to "100"),
            ) {
                tag("rect", mapOf("width" to "50", "height" to "50"))
                tag("text", mapOf("x" to "10", "y" to "20")) {
                    text("Hello")
                }
            }
            val xml = b.toString()

            // Parse with javax.xml.parsers — throws if not well-formed
            val dbf = DocumentBuilderFactory.newInstance()
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(InputSource(StringReader(xml)))
            doc.documentElement.tagName shouldBe "svg"
        }

        test("SvgBuilder escapes special characters in text content") {
            val b = SvgBuilder(pretty = false)
            b.tag("text") {
                text("<>&\"'")
            }
            val xml = b.toString()

            xml shouldContain "&lt;"
            xml shouldContain "&gt;"
            xml shouldContain "&amp;"
            xml shouldContain "&quot;"
            xml shouldContain "&apos;"
        }
    })
