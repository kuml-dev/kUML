package dev.kuml.jetbrains.markdown

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Loads the bundled bridge script as a raw classpath resource (no JS engine, no
 * browser) and asserts on its source — a cheap regression guard for the things that
 * would otherwise only surface in a manual `runIde` smoke test: that the script still
 * wires up the expected event names / DOM watching mechanism, that it never grows an
 * external-resource or `eval` dependency (this module bundles no CDN/network access
 * anywhere — see the SSRF line in the security checklist), and — the point that
 * actually broke once already (see the CRITICAL finding this closes) — that its
 * fence-recognition regexes classify the SAME info-string variants as kuml fences that
 * [KumlMarkdownFenceInfo.isKumlFence] does on the PSI side. The two sides run on
 * different representations (a rebuilt DOM `class` attribute vs. the fence's raw info
 * string) and disagreeing about which fences count silently shifts the DOM/PSI ordinal
 * mapping — a fence renders with a DIFFERENT fence's theme/name instead of just failing
 * loudly.
 */
class KumlMarkdownPreviewBridgeScriptTest :
    FunSpec({
        val script by lazy {
            val stream =
                KumlMarkdownPreviewBridgeScriptTest::class.java
                    .getResourceAsStream("/kuml/kuml-markdown-preview.js")
                    ?: error("kuml-markdown-preview.js not found on test classpath")
            stream.bufferedReader().use { it.readText() }
        }

        test("wires up MutationObserver and the language-kuml fence selector") {
            script shouldContain "MutationObserver"
            script shouldContain "language-kuml"
        }

        test("declares both the request and response event tags") {
            script shouldContain "kuml.markdown.render.request"
            script shouldContain "kuml.markdown.render.response"
        }

        test("never references an external URL or eval") {
            script shouldNotContain "eval("
            script shouldNotContain "http://"
            script shouldNotContain "https://"
        }

        test("a rendered fence's <pre> is hidden in place, never removed from the DOM") {
            // The ordinal the browser sends is a COUNT over the live `pre > code` set,
            // while the IDE side counts over the pristine PSI. Removing each fence as it
            // renders made the two counts diverge by the number of already-rendered
            // fences, so from the second pass onward every ordinal was wrong. Hiding the
            // <pre> instead keeps the counts identical for the document's whole lifetime.
            withClue("the rendered container must be inserted next to the <pre>, not swapped for it") {
                script shouldContain "insertBefore"
                script shouldContain "display"
            }
            withClue("replaceChild may only ever target a previously rendered container") {
                // The only remaining replaceChild call replaces the PREVIOUS rendered
                // container (re-render on edit), never `pre` itself.
                script shouldNotContain "replaceChild(container, entry.pre)"
                script shouldContain "replaceChild(container, previous)"
            }
        }

        test("an already-rendered fence is not re-requested on every MutationObserver tick") {
            // Consequence of keeping the <pre> in the DOM: without a per-element
            // rendered-source marker, every DOM mutation would re-enqueue every fence.
            script shouldContain "__kumlRenderedSource"
        }

        test("the in-flight marker is cleared on every terminal path, so a fence can never wedge") {
            // collectPending() skips a fence whose in-flight request still matches its
            // content — so if `data-kuml-pending` is never cleared, that fence stops
            // rendering forever with no retry and no visible error.
            script shouldContain "removeAttribute(\"data-kuml-pending\")"
        }

        test(
            "JS class-token fence detection agrees with the PSI-side isKumlFence, " +
                "for both plain and attributed fences, once IntelliJ's class-attribute " +
                "rebuild is applied to the same info string",
        ) {
            // IntelliJ's DefaultCodeFenceGeneratingProvider does not keep a fence's info
            // string verbatim in the `class` attribute — it rebuilds it as
            // "language-" + trimmed.split(" ").joinToString("-") (empirically verified
            // against intellij.markdown.jar bytecode across IDE builds 243–262, see the
            // CRITICAL finding this test closes, and the identical comment atop the
            // bridge script itself).
            fun rebuiltClassAttribute(infoString: String): String = "language-" + infoString.trim().split(" ").joinToString("-")

            // Browsers split an element's `class` attribute on ANY ASCII whitespace
            // (space, tab, newline, ...) into classList tokens — including a literal tab
            // that IntelliJ's join (which only splits on the space character) leaves
            // embedded in the rebuilt attribute value. isKumlFence(codeEl) in the bridge
            // walks codeEl.classList for exactly this reason (see its comment).
            //
            // NOTE this stops short of modeling one further real-HTML wrinkle: a literal
            // `"` inside the rebuilt class value (e.g. from a braced attribute using
            // double-quoted values) would end the `class="..."` attribute early when the
            // provider inserts it unescaped, truncating the DOM's actual class string at
            // that quote. That has no bearing on correctness here — KUML_BRACE_CLASS_RE
            // only asserts a PREFIX ("language-kuml-{" or "language-kuml{"), which
            // survives truncation regardless of what follows — but it does mean this
            // helper is not a fully faithful stand-in for a real browser's attribute
            // parser; treat it as a classList-token splitter only, not a full HTML
            // attribute-value parser.
            fun classListTokens(classAttribute: String): List<String> = classAttribute.split(Regex("""\s+""")).filter { it.isNotEmpty() }

            val exactClass =
                Regex("""var\s+KUML_EXACT_CLASS\s*=\s*"([^"]*)"\s*;""")
                    .find(script)
                    ?.groupValues
                    ?.get(1)
                    ?: error("KUML_EXACT_CLASS literal not found in bridge script")

            fun jsRegexFromScript(varName: String): Regex {
                val match =
                    Regex("""var\s+$varName\s*=\s*/([^/]*)/([a-z]*)\s*;""")
                        .find(script)
                        ?: error("$varName regex literal not found in bridge script")
                val (pattern, flags) = match.destructured
                val options = if (flags.contains("i")) setOf(RegexOption.IGNORE_CASE) else emptySet()
                return Regex(pattern, options)
            }

            val braceRe = jsRegexFromScript("KUML_BRACE_CLASS_RE")
            val attrRe = jsRegexFromScript("KUML_ATTR_CLASS_RE")
            val bareRe = jsRegexFromScript("KUML_BAREWORD_CLASS_RE")

            // Extracted the same way as the regex literals above — the exclusion list is
            // itself part of the bridge script's shipped behavior (see its
            // KUML_SIBLING_LANG_SUFFIXES comment), so drift there should fail this test too.
            val siblingSuffixes =
                Regex("""var\s+KUML_SIBLING_LANG_SUFFIXES\s*=\s*\[([^]]*)]\s*;""")
                    .find(script)
                    ?.groupValues
                    ?.get(1)
                    ?.split(",")
                    ?.map { it.trim().trim('"').lowercase() }
                    ?.filter { it.isNotEmpty() }
                    ?: error("KUML_SIBLING_LANG_SUFFIXES literal not found in bridge script")

            // Mirrors isKumlClassToken(token) from the bridge script — evaluated against
            // the regex literals (and exclusion list) extracted straight out of the
            // shipped JS source above, so this test fails if the bundled script's actual
            // patterns drift, not just a hand-copied duplicate of them.
            fun jsIsKumlFence(classAttribute: String): Boolean =
                classListTokens(classAttribute).any { token ->
                    token.lowercase() == exactClass.lowercase() ||
                        braceRe.containsMatchIn(token) ||
                        attrRe.containsMatchIn(token) ||
                        bareRe.find(token)?.let { match ->
                            match.groupValues[1].lowercase() !in siblingSuffixes
                        } == true
                }

            val infoStringVariants =
                listOf(
                    "kuml" to true,
                    "KUML" to true,
                    "kuml {theme=\"plain\" name=\"order\"}" to true,
                    "kuml theme=plain name=order" to true,
                    "kuml\tname=diag" to true,
                    // Bareword info string with no "=" and no "{" — the intermediate state
                    // of EVERY keystroke while typing an attribute (e.g. "kuml t", "kuml
                    // th", ..., "kuml theme", all before "=plain" is typed) as well as a
                    // deliberately attribute-free fence. Both sides must agree these are
                    // still kuml fences (see the MAJOR finding this fixture closes).
                    "kuml theme" to true,
                    "kuml diagram1" to true,
                    "kotlin" to false,
                    "java" to false,
                    "kuml-custom" to false,
                    // A real sibling fence language (unrelated to kUML's own "kuml"
                    // language, merely sharing the "kuml-" prefix) that itself carries a
                    // key=value attribute — the old KUML_ATTR_CLASS_RE matched this
                    // because it only checked "does '=' appear anywhere", not whether it
                    // belongs to the very first segment after "kuml-" (see the MAJOR
                    // finding this fixture closes; kuml-animated/kuml-include are real
                    // fence languages already used elsewhere in this repo and the vault).
                    "kuml-animated speed=2" to false,
                    "kuml-include foo=bar" to false,
                    "" to false,
                )

            for ((infoString, expectedKuml) in infoStringVariants) {
                // PSI side: KumlMarkdownFenceInfo.isKumlFence operates on the fence's raw
                // info string directly (fenceLookupFor's filter).
                withClue("PSI-side isKumlFence(\"$infoString\")") {
                    KumlMarkdownFenceInfo.isKumlFence(infoString) shouldBe expectedKuml
                }
                // DOM side: the bridge script only ever sees the rebuilt class attribute.
                val classAttribute = rebuiltClassAttribute(infoString)
                withClue("JS-side isKumlFence for class=\"$classAttribute\" (from info string \"$infoString\")") {
                    jsIsKumlFence(classAttribute) shouldBe expectedKuml
                }
            }

            // Sanity check that the fixture set itself actually exercises both outcomes
            // (a vacuously-true loop above would still pass if every case were false).
            infoStringVariants.any { it.second }.shouldBeTrue()
            infoStringVariants.any { !it.second }.shouldBeTrue()
        }

        test("kuml-custom (an unrelated hyphenated language, no attributes) is never misdetected as a kuml fence") {
            // Guards the specific ambiguity the class-attribute rebuild introduces: a
            // bare, space-free "kuml-custom" info string produces the exact same kind of
            // hyphen-joined class token ("language-kuml-custom") that a REAL attributed
            // kuml fence would (e.g. "language-kuml-theme=plain"). The JS regexes must
            // tell them apart using the "{" / "=" that only genuine kuml attributes
            // contribute — this is what stops that from becoming a false positive.
            KumlMarkdownFenceInfo.isKumlFence("kuml-custom").shouldBeFalse()
        }
    })
