package dev.kuml.desktop.about

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL

private class MapClassLoader(
    private val resources: Map<String, ByteArray>,
) : ClassLoader(null) {
    override fun getResourceAsStream(name: String): InputStream? = resources[name]?.let { ByteArrayInputStream(it) }

    override fun getResource(name: String): URL? = null
}

private const val FONT_DIR = "kuml-docs/x/src/main/resources/dev/kuml/workspace/pdf/fonts"

class LicenseTextLoaderTest :
    FunSpec({
        test("real load contains NOTICE, new copyright and both font licenses") {
            val text = LicenseTextLoader().load().shouldNotBeNull()
            text shouldContain "Irakli Betchvaia"
            text shouldNotContain "the kUML authors"
            text shouldContain "--- Inter-OFL.txt ---"
            text shouldContain "--- JetBrainsMono-OFL.txt ---"
            text shouldContain "SIL OPEN FONT LICENSE"
        }

        test("missing NOTICE gives null") {
            LicenseTextLoader(MapClassLoader(emptyMap())).load().shouldBeNull()
        }

        test("hostile paths are rejected") {
            val notice =
                """
                license text: $FONT_DIR/../../../etc/passwd-OFL.txt
                license text: /abs/src/main/resources//etc/x-OFL.txt
                license text: $FONT_DIR/Readme.txt
                license text: a\src/main/resources/back\slash-OFL.txt
                license text: nothing-OFL.txt
                """.trimIndent()
            val loader =
                MapClassLoader(
                    mapOf(
                        LicenseTextLoader.NOTICE_RESOURCE to notice.toByteArray(),
                        "dev/kuml/workspace/pdf/fonts/Readme.txt" to "SECRET".toByteArray(),
                    ),
                )
            val text = LicenseTextLoader(loader).load().shouldNotBeNull()
            text shouldBe notice
            text shouldNotContain "SECRET"
        }

        test("toClasspathPath accepts only OFL files under src/main/resources") {
            LicenseTextLoader.toClasspathPath("$FONT_DIR/Inter-OFL.txt") shouldBe "dev/kuml/workspace/pdf/fonts/Inter-OFL.txt"
            LicenseTextLoader.toClasspathPath("$FONT_DIR/Inter.txt").shouldBeNull()
            LicenseTextLoader.toClasspathPath("Inter-OFL.txt").shouldBeNull()
        }

        test("a missing font resource is skipped, others still load") {
            val notice = "N\nlicense text: $FONT_DIR/A-OFL.txt\nlicense text: $FONT_DIR/B-OFL.txt"
            val loader =
                MapClassLoader(
                    mapOf(
                        LicenseTextLoader.NOTICE_RESOURCE to notice.toByteArray(),
                        "dev/kuml/workspace/pdf/fonts/B-OFL.txt" to "BBB".toByteArray(),
                    ),
                )
            val text = LicenseTextLoader(loader).load().shouldNotBeNull()
            text shouldContain "--- B-OFL.txt ---\nBBB"
            text shouldNotContain "A-OFL.txt ---"
        }

        test("an oversized resource is skipped") {
            val notice = "N\nlicense text: $FONT_DIR/Big-OFL.txt"
            val loader =
                MapClassLoader(
                    mapOf(
                        LicenseTextLoader.NOTICE_RESOURCE to notice.toByteArray(),
                        "dev/kuml/workspace/pdf/fonts/Big-OFL.txt" to ByteArray(LicenseTextLoader.MAX_BYTES + 1) { 'x'.code.toByte() },
                    ),
                )
            LicenseTextLoader(loader).load() shouldBe notice
        }
    })
