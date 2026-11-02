package dev.kuml.desktop.about

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.IOException

private class FakeBrowser(
    private val supported: Boolean = true,
    private val failure: Throwable? = null,
) : BrowserPort {
    val opened = mutableListOf<AboutLink>()

    override fun isSupported() = supported

    override fun browse(link: AboutLink) {
        failure?.let { throw it }
        opened += link
    }
}

class BrowserPortTest :
    FunSpec({
        test("unsupported platform yields UNSUPPORTED and never browses") {
            val b = FakeBrowser(supported = false)
            b.openLink(AboutLink.WEBSITE) shouldBe BrowseOutcome.UNSUPPORTED
            b.opened shouldBe emptyList()
        }

        listOf(IOException("x"), SecurityException("x"), UnsupportedOperationException("x")).forEach { ex ->
            test("${ex::class.simpleName} yields FAILED") {
                FakeBrowser(failure = ex).openLink(AboutLink.LICENSE) shouldBe BrowseOutcome.FAILED
            }
        }

        test("success yields OPENED and opens that link") {
            val b = FakeBrowser()
            b.openLink(AboutLink.WEBSITE) shouldBe BrowseOutcome.OPENED
            b.opened shouldBe listOf(AboutLink.WEBSITE)
        }

        test("only the two fixed https addresses exist") {
            AboutLink.WEBSITE.url shouldBe "https://kuml.dev"
            AboutLink.LICENSE.url shouldBe "https://www.apache.org/licenses/LICENSE-2.0"
            AboutLink.entries.forEach { it.url shouldStartWith "https://" }
            AboutLink.entries.size shouldBe 2
        }
    })
