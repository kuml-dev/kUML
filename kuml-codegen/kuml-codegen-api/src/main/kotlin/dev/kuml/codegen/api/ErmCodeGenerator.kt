package dev.kuml.codegen.api

import dev.kuml.erm.model.ErmModel
import java.io.File

/**
 * Plugin API for kUML code generators that consume a typed [ErmModel] directly
 * (the "ERM-first" entry point), as opposed to [KumlCodeGenerator] which consumes
 * the generic UML [dev.kuml.core.model.KumlDiagram].
 *
 * Mirrors [KumlCodeGenerator] one-to-one. Kept as a separate interface — rather
 * than widening [KumlCodeGenerator] to a union type — because the two source
 * models ([dev.kuml.core.model.KumlDiagram] and [ErmModel]) are structurally
 * unrelated; a single `generate` overload set would force every existing UML
 * generator to also handle ERM input it doesn't need.
 *
 * V3.4.7 introduces this alongside [ErmCodeGenRegistry] so that `kuml-gen-sql`'s
 * `ErmSqlDdlGenerator` (and future ERM-consuming generators, e.g. V3.4.8's
 * `erm-to-exposed`) can be addressed by id from an `ermModel { … }` script without
 * going through the `UmlToErmTransformer` chain first.
 */
public interface ErmCodeGenerator {
    /** Unique identifier, e.g. `"sql"`. May collide with a [KumlCodeGenerator] id of the same name — the two are looked up in separate registries. */
    public val id: String

    /** Human-readable name shown in `kuml generate --help`. */
    public val displayName: String

    /**
     * Generate code from [model] into [outputDir].
     *
     * @param model The source ERM model.
     * @param outputDir Directory where generated files are written. Created if absent.
     * @param options Generator-specific options (dialect, flags, …).
     * @return Ordered list of files that were written.
     */
    public fun generate(
        model: ErmModel,
        outputDir: File,
        options: Map<String, String>,
    ): List<File>
}

/**
 * SPI for [ErmCodeGenerator] plugin registration via [java.util.ServiceLoader].
 *
 * Analogous to [KumlCodeGeneratorProvider] — implementations register via
 * `META-INF/services/dev.kuml.codegen.api.ErmCodeGeneratorProvider`. Kept as a
 * distinct service-loader file from [KumlCodeGeneratorProvider] so an id like
 * `"sql"` can be registered independently in each registry without collision.
 */
public interface ErmCodeGeneratorProvider {
    /** Liefert eine Generator-Instanz. Wird bei jedem [ErmCodeGenRegistry.get]-Aufruf gerufen. */
    public fun generator(): ErmCodeGenerator
}
