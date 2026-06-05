package dev.kuml.codegen.api

/**
 * SPI für Codegen-Plugin-Registrierung via [java.util.ServiceLoader].
 *
 * Implementierungen leben typischerweise im jeweiligen Plugin-Modul
 * (`kuml-gen-kotlin`, `kuml-gen-java`, `kuml-gen-sql`) und melden sich
 * über `META-INF/services/dev.kuml.codegen.api.KumlCodeGeneratorProvider` an.
 *
 * Pattern-Reuse aus `dev.kuml.profile.KumlProfileProvider` und
 * `dev.kuml.renderer.theme.core.KumlThemeProvider` (V1.1.3).
 */
public interface KumlCodeGeneratorProvider {
    /** Liefert eine Generator-Instanz. Wird bei jedem `CodeGenRegistry.get(...)`-Aufruf gerufen. */
    public fun generator(): KumlCodeGenerator
}
