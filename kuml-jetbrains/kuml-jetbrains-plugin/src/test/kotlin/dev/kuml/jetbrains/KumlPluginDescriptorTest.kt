package dev.kuml.jetbrains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Verifies the static plugin descriptor split introduced when the Kotlin-plugin
 * dependency was made optional.
 *
 * Reads the descriptors from the test classpath rather than loading the full
 * IntelliJ plugin descriptor pipeline, so no IntelliJ runtime is needed.
 *
 * Two descriptors exist:
 *  - META-INF/plugin.xml               — always loaded, must stay Kotlin-plugin-free
 *  - META-INF/kuml-kotlin-support.xml  — config-file fragment, loaded ONLY when the
 *                                        Kotlin plugin is present
 */
class KumlPluginDescriptorTest :
    FunSpec({

        fun readResource(path: String): String {
            val stream =
                KumlPluginDescriptorTest::class.java.classLoader
                    .getResourceAsStream(path)
                    ?: error("$path not found on test classpath")
            return stream.bufferedReader().use { it.readText() }
        }

        val pluginXml: String by lazy { readResource("META-INF/plugin.xml") }
        val kotlinSupportXml: String by lazy { readResource("META-INF/kuml-kotlin-support.xml") }
        val markdownSupportXml: String by lazy { readResource("META-INF/kuml-markdown-support.xml") }
        val asciidocSupportXml: String by lazy { readResource("META-INF/kuml-asciidoc-support.xml") }

        // ── (3) Extensions that MUST work without the Kotlin plugin ──────────────
        // These are the whole point of the optional-dependency fix: on WebStorm,
        // PyCharm, GoLand, Rider, CLion, DataGrip and RubyMine the plugin must still
        // load and provide icon + split-view preview + settings page.

        test("plugin.xml registers KumlSplitEditorProvider as fileEditorProvider") {
            pluginXml shouldContain "KumlSplitEditorProvider"
            pluginXml shouldContain "fileEditorProvider"
        }

        test("plugin.xml registers the Kotlin-plugin-free base extensions") {
            pluginXml shouldContain "KumlFileIconProvider"
            pluginXml shouldContain "fileIconProvider"
            pluginXml shouldContain "KumlPreviewConfigurable"
            pluginXml shouldContain "applicationConfigurable"
            pluginXml shouldContain "defaultLiveTemplates"
            pluginXml shouldContain "notificationGroup"
        }

        // ── (1) Optional dependency declaration ──────────────────────────────────
        // A hard <depends>org.jetbrains.kotlin</depends> makes the IntelliJ Platform
        // refuse to load the ENTIRE plugin on any IDE without the Kotlin plugin
        // ("Plugin kUML requires plugin org.jetbrains.kotlin to be installed") —
        // no icon, no preview, no file recognition at all.
        test("plugin.xml declares org.jetbrains.kotlin as an optional dependency with a config-file") {
            pluginXml shouldContain
                "<depends optional=\"true\" config-file=\"kuml-kotlin-support.xml\">org.jetbrains.kotlin</depends>"
        }

        test("plugin.xml declares org.intellij.plugins.markdown as an optional dependency with a config-file") {
            pluginXml shouldContain
                "<depends optional=\"true\" config-file=\"kuml-markdown-support.xml\">org.intellij.plugins.markdown</depends>"
        }

        test("plugin.xml declares org.asciidoctor.intellij.asciidoc as an optional dependency with a config-file") {
            pluginXml shouldContain
                "<depends optional=\"true\" config-file=\"kuml-asciidoc-support.xml\">org.asciidoctor.intellij.asciidoc</depends>"
        }

        test("plugin.xml keeps com.intellij.modules.platform as the only hard dependency") {
            pluginXml shouldContain "<depends>com.intellij.modules.platform</depends>"
            // A bare, non-optional Kotlin/Markdown/AsciiDoc dependency must never come back.
            pluginXml shouldNotContain "<depends>org.jetbrains.kotlin</depends>"
            pluginXml shouldNotContain "<depends>org.intellij.plugins.markdown</depends>"
            pluginXml shouldNotContain "<depends>org.asciidoctor.intellij.asciidoc</depends>"
        }

        // ── (2) Fragment content ─────────────────────────────────────────────────

        test("kuml-kotlin-support.xml is present on the classpath and is a valid fragment root") {
            kotlinSupportXml shouldContain "<idea-plugin>"
            // A config-file fragment must not repeat descriptor identity.
            kotlinSupportXml shouldNotContain "<id>"
            kotlinSupportXml shouldNotContain "<depends>"
        }

        test("kuml-kotlin-support.xml registers the Kotlin script definitions source") {
            kotlinSupportXml shouldContain "defaultExtensionNs=\"org.jetbrains.kotlin\""
            kotlinSupportXml shouldContain "scriptDefinitionsSource"
            kotlinSupportXml shouldContain "dev.kuml.jetbrains.KumlScriptDefinitionsSource"
        }

        // K2-Kompatibilität (Guard aus v0.8.0, jetzt im Fragment): Ohne diese
        // Deklaration deaktiviert IntelliJ 2024.x+ das Plugin im K2-Mode
        // ("Plugin is incompatible with the Kotlin plugin in 'K2' mode").
        // Das Plugin nutzt keine K1-Resolve-APIs (geprüft via Import-Scan),
        // darum ist supportsK2="true" sicher.
        test("kuml-kotlin-support.xml declares supportsKotlinPluginMode for both K1 and K2") {
            kotlinSupportXml shouldContain "supportsKotlinPluginMode"
            kotlinSupportXml shouldContain "supportsK1=\"true\""
            kotlinSupportXml shouldContain "supportsK2=\"true\""
        }

        test("kuml-kotlin-support.xml registers all five Kotlin-bound com.intellij extensions") {
            listOf(
                "externalAnnotator" to "dev.kuml.jetbrains.KumlAnnotator",
                "lang.psiStructureViewFactory" to "dev.kuml.jetbrains.KumlStructureViewBuilderProvider",
                "lang.foldingBuilder" to "dev.kuml.jetbrains.folding.KumlFoldingBuilder",
                "completion.contributor" to "dev.kuml.jetbrains.completion.KumlCompletionContributor",
                "renameHandler" to "dev.kuml.jetbrains.rename.KumlRenameHandler",
            ).forEach { (extensionPoint, implementationClass) ->
                kotlinSupportXml shouldContain extensionPoint
                kotlinSupportXml shouldContain implementationClass
            }
        }

        test("kuml-markdown-support.xml is present on the classpath and is a valid fragment root") {
            markdownSupportXml shouldContain "<idea-plugin>"
            markdownSupportXml shouldNotContain "<id>"
            markdownSupportXml shouldNotContain "<depends>"
        }

        test("kuml-markdown-support.xml registers browserPreviewExtensionProvider, fenceLanguageProvider and lineMarkerProvider") {
            markdownSupportXml shouldContain "defaultExtensionNs=\"org.intellij.markdown\""
            markdownSupportXml shouldContain "browserPreviewExtensionProvider"
            markdownSupportXml shouldContain "dev.kuml.jetbrains.markdown.KumlMarkdownPreviewExtension\$Provider"
            markdownSupportXml shouldContain "fenceLanguageProvider"
            markdownSupportXml shouldContain "dev.kuml.jetbrains.markdown.KumlMarkdownCodeFenceLanguageProvider"
            markdownSupportXml shouldContain "codeInsight.lineMarkerProvider"
            markdownSupportXml shouldContain "dev.kuml.jetbrains.markdown.KumlMarkdownLineMarkerProvider"
        }

        // Regression guard (2026-09): the internal CodeFenceGeneratingProvider EP blocked
        // every JetBrains Marketplace submission with an INTERNAL_API_USAGES verifier
        // failure — see KumlMarkdownPreviewExtension's kdoc. Never let it come back.
        test("kuml-markdown-support.xml never re-registers the internal fenceGeneratingProvider EP") {
            markdownSupportXml shouldNotContain "fenceGeneratingProvider"
            markdownSupportXml shouldNotContain "CodeFenceGeneratingProvider"
        }

        test("kuml-asciidoc-support.xml is present on the classpath and is a valid fragment root") {
            asciidocSupportXml shouldContain "<idea-plugin>"
            asciidocSupportXml shouldNotContain "<id>"
            asciidocSupportXml shouldNotContain "<depends>"
        }

        test("kuml-asciidoc-support.xml registers html panel provider, line marker and language injector") {
            asciidocSupportXml shouldContain "defaultExtensionNs=\"org.asciidoc.intellij\""
            asciidocSupportXml shouldContain "html.panel.provider"
            asciidocSupportXml shouldContain "dev.kuml.jetbrains.asciidoc.KumlAsciidocHtmlPanelProvider"
            asciidocSupportXml shouldContain "codeInsight.lineMarkerProvider"
            asciidocSupportXml shouldContain "dev.kuml.jetbrains.asciidoc.KumlAsciidocLineMarkerProvider"
            asciidocSupportXml shouldContain "multiHostInjector"
            asciidocSupportXml shouldContain "dev.kuml.jetbrains.asciidoc.KumlAsciidocLanguageInjector"
        }

        // ── (c) Re-merge guard ───────────────────────────────────────────────────
        // If someone later moves a Kotlin-/Markdown-/AsciiDoc-bound extension back into plugin.xml, the
        // plugin silently stops loading on IDEs without those plugins again. Assert on class
        // names, NOT on the string "org.jetbrains.kotlin" / "org.intellij.plugins.markdown":
        // those literals legitimately appear in plugin.xml's <description> CDATA.
        test("plugin.xml contains none of the Kotlin-, Markdown- or AsciiDoc-bound extension implementations") {
            listOf(
                "KumlScriptDefinitionsSource",
                "KumlAnnotator",
                "KumlStructureViewBuilderProvider",
                "KumlFoldingBuilder",
                "KumlCompletionContributor",
                "KumlRenameHandler",
                "KumlMarkdownPreviewExtension",
                "KumlMarkdownCodeFenceLanguageProvider",
                "KumlMarkdownLineMarkerProvider",
                "KumlAsciidocHtmlPanelProvider",
                "KumlAsciidocLineMarkerProvider",
                "KumlAsciidocLanguageInjector",
                "KumlAsciidocReferencedFileWatcher",
                "KumlAsciidocHtmlRewriter",
                "KumlAsciidocBlockParser",
                "KumlAsciidocPathGuard",
            ).forEach { className -> pluginXml shouldNotContain className }
        }

        // Anchored on the literal XML element open-tag (leading "<"), not a bare substring:
        // both "language=\"kotlin\"" and "supportsKotlinPluginMode" legitimately appear as
        // prose/code-samples inside the human-readable <change-notes> history (e.g. the
        // 0.8.0 changelog entry documents the K2-compatibility fix using that exact
        // attribute name inside a <code> tag). A bare-substring check would false-positive
        // on every future release note that quotes these names.
        test("plugin.xml declares no language-bound extensions") {
            pluginXml shouldNotContain "<supportsKotlinPluginMode"
            pluginXml shouldNotContain "language=\"kotlin\""
        }
    })
