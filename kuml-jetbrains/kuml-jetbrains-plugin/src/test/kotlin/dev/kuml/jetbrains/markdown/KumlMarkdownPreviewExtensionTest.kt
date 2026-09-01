package dev.kuml.jetbrains.markdown

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Constructs [KumlMarkdownPreviewExtension] with `pipe = null` — exercising it fully
 * without needing a JCEF panel or a live IntelliJ project, per the module's "no
 * IntelliJ Platform Test Framework" constraint (see `build.gradle.kts`).
 */
class KumlMarkdownPreviewExtensionTest :
    FunSpec({
        fun newExtension(fenceLookup: (Int, String) -> Pair<String, String>? = { _, _ -> null }) =
            KumlMarkdownPreviewExtension(pipe = null, fenceLookup = fenceLookup)

        test("scripts exposes exactly the bundled bridge script") {
            newExtension().scripts shouldBe listOf("kuml-markdown-preview.js")
        }

        test("priority is DEFAULT") {
            newExtension().priority shouldBe
                org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension.Priority.DEFAULT
        }

        test("resourceProvider is the extension itself") {
            val extension = newExtension()
            extension.resourceProvider shouldBe extension
        }

        test("canProvide is true only for the exact bridge script name") {
            val extension = newExtension()
            extension.canProvide("kuml-markdown-preview.js") shouldBe true
            extension.canProvide("mermaid.min.js") shouldBe false
            extension.canProvide("KUML-MARKDOWN-PREVIEW.JS") shouldBe false
            // Path traversal guard: exact match only, never endsWith/contains.
            extension.canProvide("../../META-INF/plugin.xml") shouldBe false
            extension.canProvide("evil/kuml-markdown-preview.js") shouldBe false
            extension.canProvide("kuml-markdown-preview.js/../plugin.xml") shouldBe false
        }

        test("loadResource returns the bridge script content as text/javascript") {
            val extension = newExtension()
            val resource = extension.loadResource("kuml-markdown-preview.js")
            requireNotNull(resource)
            resource.type shouldBe "text/javascript"
            val content = String(resource.content, Charsets.UTF_8)
            content shouldContain "__IntelliJTools"
            content shouldContain "kuml.markdown.render.request"
            content shouldContain "kuml.markdown.render.response"
        }

        test("loadResource returns null for any other resource name") {
            val extension = newExtension()
            extension.loadResource("mermaid.min.js") shouldBe null
            extension.loadResource("../../META-INF/plugin.xml") shouldBe null
        }

        test("dispose on a null pipe does not throw") {
            val extension = newExtension()
            shouldNotThrowAny { extension.dispose() }
        }

        test("onRequest on a well-formed payload does not throw") {
            // No live IntelliJ Application in this plain Kotest unit test (this module
            // deliberately runs without the IntelliJ Platform test framework — see
            // build.gradle.kts), so ApplicationManager.getApplication() is null here and
            // onRequest degrades to a no-op past decoding. The actual render+respond path
            // is covered by the manual runIde smoke test, not a unit test.
            val extension = newExtension()
            shouldNotThrowAny {
                val payload = KumlMarkdownPreviewProtocol.encodeResponse("req-1", 0, "classDiagram { classOf(\"X\") }")
                extension.onRequest(payload)
            }
        }

        test("onRequest silently ignores a malformed payload") {
            val extension = newExtension()
            shouldNotThrowAny { extension.onRequest("garbage") }
        }

        test("an unexpected render failure still produces a visible error container, never an empty response") {
            // Every accepted request must produce exactly one response: the bridge script
            // marks a fence in-flight and skips it while that request stands, so a dropped
            // response wedges the fence permanently (blank, no retry, no error shown).
            val html = KumlMarkdownFenceHtml.renderError(IllegalStateException("kuml CLI not found"))
            html shouldContain "kuml-diagram-error"
            html shouldContain "kuml CLI not found"
        }

        test("renderError falls back to the exception type when the message is blank") {
            val html = KumlMarkdownFenceHtml.renderError(IllegalStateException("   "))
            html shouldContain "kuml-diagram-error"
            html shouldContain "IllegalStateException"
        }
    })
