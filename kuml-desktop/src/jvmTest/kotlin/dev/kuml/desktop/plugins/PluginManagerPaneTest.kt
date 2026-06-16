package dev.kuml.desktop.plugins

import dev.kuml.codegen.m2m.TransformerRegistry
import dev.kuml.renderer.theme.core.ThemeRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * V3.0.13 — Unit-Tests für Plugin-Manager-Logik.
 *
 * Kein Compose-Test-Framework nötig — die Lade-Funktionen sind `internal`
 * und direkt testbar.
 */
class PluginManagerPaneTest : FunSpec({

    // ── PluginEntry data class ─────────────────────────────────────────────

    test("PluginEntry speichert id, kind und description korrekt") {
        val entry = PluginEntry(id = "jpa", kind = "Transformer", description = "UML zu JPA")
        entry.id shouldBe "jpa"
        entry.kind shouldBe "Transformer"
        entry.description shouldBe "UML zu JPA"
    }

    test("PluginEntry mit leerem description ist gültig") {
        val entry = PluginEntry(id = "plain", kind = "Theme", description = "")
        entry.id shouldNotBe null
        entry.description shouldBe ""
    }

    test("PluginEntry equals und hashCode sind strukturell korrekt (data class)") {
        val a = PluginEntry("id1", "Theme", "desc")
        val b = PluginEntry("id1", "Theme", "desc")
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    // ── loadTransformers() ─────────────────────────────────────────────────

    test("loadTransformers() gibt Liste zurück — kein Crash auch wenn Registry leer") {
        // TransformerRegistry enthält im Test-Classpath evtl. keine Provider
        val transformers = loadTransformers()
        transformers shouldNotBe null
        // Liste darf leer sein — darf aber nicht null sein oder werfen
    }

    test("loadTransformers() liefert nur Einträge mit nicht-leerem id") {
        val transformers = loadTransformers()
        transformers.forEach { entry ->
            entry.id.shouldNotBeBlank()
            entry.kind shouldBe "Transformer"
        }
    }

    test("loadTransformers() registrierter Transformer erscheint in der Liste") {
        TransformerRegistry.clear()
        // Minimaler Stub-Transformer
        val stubTransformer = object : dev.kuml.codegen.m2m.KumlTransformer<Any, Any> {
            override val id = "test-stub"
            override val description = "Stub für Unit-Test"
            override fun transform(
                source: Any,
                ctx: dev.kuml.codegen.m2m.TransformContext,
            ) = dev.kuml.codegen.m2m.TransformResult.Success(
                output = Unit,
                trace = dev.kuml.codegen.m2m.TransformTrace(),
            )
        }
        TransformerRegistry.register(stubTransformer)
        try {
            val entries = loadTransformers()
            entries.map { it.id } shouldBe listOf("test-stub")
            entries.first().description shouldBe "Stub für Unit-Test"
        } finally {
            TransformerRegistry.clear()
        }
    }

    // ── loadReverseEngines() ───────────────────────────────────────────────

    test("loadReverseEngines() gibt Liste zurück — kein Crash wenn Klasse fehlt (Reflection-Fallback)") {
        // Im Test-Classpath ist ReverseEngineRegistry evtl. nicht vorhanden →
        // die Reflection-basierte Implementierung muss gracefully emptyList() zurückgeben
        val engines = loadReverseEngines()
        engines shouldNotBe null
    }

    // ── loadThemes() ───────────────────────────────────────────────────────

    test("loadThemes() liefert Themes wenn ThemeRegistry befüllt ist") {
        ThemeRegistry.clear()
        // Stub-Provider registrieren
        ThemeRegistry.register(object : dev.kuml.renderer.theme.core.KumlThemeProvider {
            override val name = "test-theme"
            override fun theme(): dev.kuml.renderer.theme.core.KumlTheme =
                dev.kuml.renderer.theme.core.PlainTheme()
        })
        try {
            val themes = loadThemes()
            themes.shouldNotBeEmpty()
            themes.first().id shouldBe "test-theme"
            themes.first().kind shouldBe "Theme"
        } finally {
            ThemeRegistry.clear()
        }
    }

    test("loadThemes() gibt leere Liste zurück wenn Registry leer") {
        ThemeRegistry.clear()
        loadThemes().shouldBeEmpty()
    }

    test("loadThemes() sortiert Themes alphabetisch (via ThemeRegistry.names())") {
        ThemeRegistry.clear()
        listOf("zebra", "alpha", "mango").forEach { name ->
            ThemeRegistry.register(object : dev.kuml.renderer.theme.core.KumlThemeProvider {
                override val name = name
                override fun theme(): dev.kuml.renderer.theme.core.KumlTheme =
                    dev.kuml.renderer.theme.core.PlainTheme()
            })
        }
        try {
            val ids = loadThemes().map { it.id }
            ids shouldBe listOf("alpha", "mango", "zebra")
        } finally {
            ThemeRegistry.clear()
        }
    }
})
