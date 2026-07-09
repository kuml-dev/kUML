package dev.kuml.codegen.reverse.erm.registry

import dev.kuml.codegen.reverse.erm.ErmReverseEngine
import java.util.ServiceLoader

/** ServiceLoader wrapper that exposes all registered ERM reverse engines (V3.4.9). */
public object ErmReverseEngineRegistry {
    /** All currently visible engines (classpath snapshot at call time). */
    public fun all(): List<ErmReverseEngine> = ServiceLoader.load(ErmReverseEngine::class.java).toList()

    /** Find an engine by id. Returns null when not registered. */
    public fun byId(id: String): ErmReverseEngine? = all().firstOrNull { it.id == id }

    /** Find an engine by SQL dialect, e.g. `"postgres"`. Returns null when not registered. */
    public fun byDialect(dialect: String): ErmReverseEngine? = all().firstOrNull { it.dialect == dialect }
}
