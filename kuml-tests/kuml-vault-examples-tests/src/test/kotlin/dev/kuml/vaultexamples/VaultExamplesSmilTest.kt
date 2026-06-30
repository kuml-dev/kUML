package dev.kuml.vaultexamples

import dev.kuml.io.svg.uml.smil.SequenceAnimationContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * SMIL animation tests for the three animated vault examples (V3.2).
 *
 * Loads each example from the classpath (synced from the vault via sync-vault-examples.sh),
 * extracts the embedded trace JSON, renders the animated SVG, and asserts that:
 *  - [dev.kuml.io.svg.bpmn.smil.AnimatedBpmnRenderResult.hasAnimation] / [dev.kuml.io.svg.stm.smil.AnimatedStmRenderResult.hasAnimation] is true
 *  - The SVG contains expected SMIL elements
 *  - The SMIL-stripped snapshot contains none of the SMIL animation elements
 *
 * All three animated examples also pass through the static loop in [VaultExamplesRenderTest] — the
 * BPMN one as [dev.kuml.core.script.ExtractedDiagram.Bpmn], the STM one as
 * [dev.kuml.core.script.ExtractedDiagram.Uml], and the UML Sequence one as
 * [dev.kuml.core.script.ExtractedDiagram.Uml] — so regression coverage is double.
 *
 * V3.2 — UML Sequence Diagram SMIL Animation
 */
class VaultExamplesSmilTest :
    StringSpec({

        // ── Markdown content helpers ──────────────────────────────────────────

        /** Extracts the first ` ```json ` fenced block from [markdown]. */
        fun extractTraceJson(markdown: String): String {
            val regex = Regex("```json\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            return regex.find(markdown)?.groupValues?.get(1)
                ?: error("Kein ```json Block in Markdown gefunden")
        }

        /** Extracts the first ` ```kuml ` fenced block from [markdown]. */
        fun extractKumlScript(markdown: String): String {
            val regex = Regex("```kuml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            return regex.find(markdown)?.groupValues?.get(1)
                ?: error("Kein ```kuml Block in Markdown gefunden")
        }

        /** Loads a vault-examples classpath resource by filename. */
        fun loadResource(filename: String): String =
            VaultExamplesSmilTest::class.java
                .getResourceAsStream("/vault-examples/$filename")
                ?.bufferedReader(Charsets.UTF_8)
                ?.readText()
                ?: error("Classpath-Ressource nicht gefunden: /vault-examples/$filename")

        // ── SMIL output directory ─────────────────────────────────────────────

        val smilOutputDir = "vault-examples/smil"

        // ── (1) BPMN animiert: PdV Mitgliedsantrag ───────────────────────────

        "rendert animiert: BPMN PdV Mitgliedsantrag (SMIL)" {
            VaultExampleRenderer.init()

            val mdContent = loadResource("07 BPMN animiert – PdV Mitgliedsantrag.md")
            val kumlScript = extractKumlScript(mdContent)
            val traceJson = extractTraceJson(mdContent)

            val result = AnimatedExampleRenderer.renderBpmn(kumlScript, traceJson)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "<animateMotion"
            result.svg shouldContain "</svg>"

            // Write animated SVG to smil/ output dir
            SampleOutput.write(
                "$smilOutputDir/07_BPMN_animiert_PdV_Mitgliedsantrag.svg",
                result.svg,
            )

            // Static snapshot via re-render with trace=null for byte-identical output
            val staticResult = AnimatedExampleRenderer.renderBpmn(kumlScript, traceJson = EMPTY_TRACE_JSON)
            SampleOutput.write(
                "vault-examples/elegant/07_BPMN_animiert_PdV_Mitgliedsantrag.svg",
                staticResult.svg,
            )

            println("[smil-test] BPMN SMIL: hasAnimation=${result.hasAnimation}, svg.length=${result.svg.length}")
        }

        // ── (2) STM animiert: Traffic Light ──────────────────────────────────

        "rendert animiert: STM Traffic Light (SMIL)" {
            VaultExampleRenderer.init()

            val mdContent = loadResource("08 STM animiert – Traffic Light.md")
            val kumlScript = extractKumlScript(mdContent)
            val traceJson = extractTraceJson(mdContent)

            val result = AnimatedExampleRenderer.renderStm(kumlScript, traceJson)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "<animate"
            result.svg shouldContain "</svg>"

            // Write animated SVG to smil/ output dir
            SampleOutput.write(
                "$smilOutputDir/08_STM_animiert_Traffic_Light.svg",
                result.svg,
            )

            // Static snapshot
            val staticResult = AnimatedExampleRenderer.renderStm(kumlScript, traceJson = EMPTY_TRACE_JSON)
            SampleOutput.write(
                "vault-examples/elegant/08_STM_animiert_Traffic_Light.svg",
                staticResult.svg,
            )

            println("[smil-test] STM SMIL: hasAnimation=${result.hasAnimation}, svg.length=${result.svg.length}")
        }

        // ── (3) SMIL strip ist deterministisch ───────────────────────────────

        "SMIL strip ist deterministisch: BPMN" {
            val mdContent = loadResource("07 BPMN animiert – PdV Mitgliedsantrag.md")
            val kumlScript = extractKumlScript(mdContent)
            val traceJson = extractTraceJson(mdContent)

            val result = AnimatedExampleRenderer.renderBpmn(kumlScript, traceJson)
            result.hasAnimation.shouldBeTrue()

            val stripped = AnimatedExampleRenderer.stripSmil(result.svg)
            stripped shouldNotContain "<animate"
            stripped shouldNotContain "<animateMotion"
            stripped shouldNotContain "<animateTransform"
            stripped shouldNotContain "<set "
        }

        "SMIL strip ist deterministisch: STM" {
            val mdContent = loadResource("08 STM animiert – Traffic Light.md")
            val kumlScript = extractKumlScript(mdContent)
            val traceJson = extractTraceJson(mdContent)

            val result = AnimatedExampleRenderer.renderStm(kumlScript, traceJson)
            result.hasAnimation.shouldBeTrue()

            val stripped = AnimatedExampleRenderer.stripSmil(result.svg)
            stripped shouldNotContain "<animate"
            stripped shouldNotContain "<animateMotion"
            stripped shouldNotContain "<animateTransform"
            stripped shouldNotContain "<set "
        }

        // ── (3) UML Sequence animiert: API Submit ──────────────────────────────

        "rendert animiert: UML Sequence API Submit (SMIL)" {
            VaultExampleRenderer.init()

            val mdContent = loadResource("19 UML Sequence animiert – API Submit.md")
            val kumlScript = extractKumlScript(mdContent)
            val traceJson = extractTraceJson(mdContent)

            val result = AnimatedExampleRenderer.renderSequence(kumlScript, traceJson)

            result.hasAnimation.shouldBeTrue()
            result.svg shouldContain "<animateMotion"
            result.svg shouldContain "kuml-seq-dot-"
            result.svg shouldContain "</svg>"

            SampleOutput.write(
                "$smilOutputDir/19_UML_Sequence_animiert_API_Submit.svg",
                result.svg,
            )

            val staticResult = AnimatedExampleRenderer.renderSequence(kumlScript, traceJson = EMPTY_TRACE_JSON)
            SampleOutput.write(
                "vault-examples/elegant/19_UML_Sequence_animiert_API_Submit.svg",
                staticResult.svg,
            )

            println("[smil-test] SEQ SMIL: hasAnimation=${result.hasAnimation}, svg.length=${result.svg.length}")
        }

        // ── (4) SMIL strip ist deterministisch: UML Sequence ─────────────────

        "SMIL strip ist deterministisch: UML Sequence" {
            val mdContent = loadResource("19 UML Sequence animiert – API Submit.md")
            val kumlScript = extractKumlScript(mdContent)
            val traceJson = extractTraceJson(mdContent)

            val seqContext = SequenceAnimationContext(loopCount = 1)
            val result =
                AnimatedExampleRenderer.renderSequence(
                    kumlScript,
                    traceJson,
                    context = seqContext,
                )
            result.hasAnimation.shouldBeTrue()

            val stripped = AnimatedExampleRenderer.stripSmil(result.svg)
            stripped shouldNotContain "<animate"
            stripped shouldNotContain "<animateMotion"
            stripped shouldNotContain "<animateTransform"
            stripped shouldNotContain "<set "
        }
    }) {
    companion object {
        /** Minimal trace JSON that produces no animations — used for static snapshot re-render. */
        const val EMPTY_TRACE_JSON = """{"schema":"kuml.trace.v1","entries":[]}"""
    }
}
