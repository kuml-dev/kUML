package dev.kuml.jetbrains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * V2.0.28b — Verifies that plugin.xml registers both new extensions introduced
 * in V2.0.28b: the file-editor provider and the structure view factory.
 *
 * Reads the plugin.xml from the test classpath rather than loading the full
 * IntelliJ plugin descriptor pipeline, so no IntelliJ runtime is needed.
 */
class KumlPluginDescriptorTest :
    FunSpec({

        val pluginXml: String by lazy {
            val stream =
                KumlPluginDescriptorTest::class.java.classLoader
                    .getResourceAsStream("META-INF/plugin.xml")
                    ?: error("plugin.xml not found on test classpath")
            stream.bufferedReader().use { it.readText() }
        }

        test("plugin.xml registers KumlSplitEditorProvider as fileEditorProvider") {
            pluginXml shouldContain "KumlSplitEditorProvider"
            pluginXml shouldContain "fileEditorProvider"
        }

        test("plugin.xml registers KumlStructureViewBuilderProvider as lang.psiStructureViewFactory") {
            pluginXml shouldContain "KumlStructureViewBuilderProvider"
            pluginXml shouldContain "lang.psiStructureViewFactory"
        }

        // K2-Kompatibilität: Ohne diese Deklaration deaktiviert IntelliJ 2024.x+
        // das Plugin im K2-Mode ("Plugin is incompatible with the Kotlin plugin in
        // 'K2' mode"). Das Plugin nutzt keine K1-Resolve-APIs (geprüft via
        // Import-Scan), darum ist supportsK2="true" sicher.
        test("plugin.xml declares supportsKotlinPluginMode for both K1 and K2") {
            pluginXml shouldContain "supportsKotlinPluginMode"
            pluginXml shouldContain "supportsK2=\"true\""
            pluginXml shouldContain "supportsK1=\"true\""
        }
    })
