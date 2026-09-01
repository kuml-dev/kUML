package dev.kuml.jetbrains.markdown

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Base64

private const val SEP = '\u001F'

/** Builds a request payload the same way the bundled bridge JS does. */
private fun buildRequestPayload(
    requestId: String,
    ordinal: Int,
    source: String,
): String =
    listOf(
        requestId,
        ordinal.toString(),
        Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8)),
    ).joinToString(SEP.toString())

class KumlMarkdownPreviewProtocolTest :
    FunSpec({
        test("decodeRequest parses a payload built the way the bridge script builds it") {
            val source = "classDiagram { classOf(\"Order\") }"
            val payload = buildRequestPayload("req-1", 3, source)

            val decoded = KumlMarkdownPreviewProtocol.decodeRequest(payload)
            decoded.shouldNotBeNull()
            decoded.requestId shouldBe "req-1"
            decoded.ordinal shouldBe 3
            decoded.fallbackSource shouldBe source
        }

        test("decodeRequest round-trips a multiline SVG payload with special characters") {
            val source =
                """
                <svg xmlns="http://www.w3.org/2000/svg">
                <text>Hello &amp; "World" &lt;tag&gt;</text>
                </svg>
                """.trimIndent()
            val decoded = KumlMarkdownPreviewProtocol.decodeRequest(buildRequestPayload("req-2", 0, source))
            decoded.shouldNotBeNull()
            decoded.fallbackSource shouldBe source
        }

        test("decodeRequest round-trips umlauts and emoji (UTF-8)") {
            val source = "Ünïcödé Straße — 😀🎉 中文"
            val decoded = KumlMarkdownPreviewProtocol.decodeRequest(buildRequestPayload("req-3", 0, source))
            decoded.shouldNotBeNull()
            decoded.fallbackSource shouldBe source
        }

        test("decodeRequest round-trips empty content") {
            val decoded = KumlMarkdownPreviewProtocol.decodeRequest(buildRequestPayload("req-4", 0, ""))
            decoded.shouldNotBeNull()
            decoded.fallbackSource shouldBe ""
        }

        test("decodeRequest round-trips a large (~200 KB) payload") {
            val large = "<span>x</span>".repeat(200 * 1024 / 14)
            val decoded = KumlMarkdownPreviewProtocol.decodeRequest(buildRequestPayload("req-5", 7, large))
            decoded.shouldNotBeNull()
            decoded.fallbackSource shouldBe large
            decoded.ordinal shouldBe 7
        }

        test("decodeRequest returns null on malformed payloads instead of throwing") {
            KumlMarkdownPreviewProtocol.decodeRequest("garbage").shouldBeNull()
            KumlMarkdownPreviewProtocol.decodeRequest("").shouldBeNull()
            KumlMarkdownPreviewProtocol.decodeRequest("only-one-field").shouldBeNull()
            KumlMarkdownPreviewProtocol.decodeRequest(listOf("id", "not-a-number", "AAAA").joinToString(SEP.toString())).shouldBeNull()
            KumlMarkdownPreviewProtocol.decodeRequest(listOf("id", "0", "not valid base64!!").joinToString(SEP.toString())).shouldBeNull()
            KumlMarkdownPreviewProtocol.decodeRequest(listOf("", "0", "AAAA").joinToString(SEP.toString())).shouldBeNull()
        }

        test("encodeResponse produces a 3-field SEP-delimited payload decodeRequest can also parse") {
            val html = "<div class=\"kuml-diagram-container\">rendered</div>"
            val encoded = KumlMarkdownPreviewProtocol.encodeResponse("req-6", 2, html)

            // encodeResponse/decodeRequest share the same wire shape (id, ordinal, base64
            // payload) — only the semantic meaning of the third field differs (rendered HTML
            // vs. fallback source). Reusing decodeRequest here is a legitimate way to verify
            // encodeResponse's Base64/UTF-8 encoding without a second decoder.
            val decoded = KumlMarkdownPreviewProtocol.decodeRequest(encoded)
            decoded.shouldNotBeNull()
            decoded.requestId shouldBe "req-6"
            decoded.ordinal shouldBe 2
            decoded.fallbackSource shouldBe html
        }
    })
