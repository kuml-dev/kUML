package dev.kuml.core.script

import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.KumlDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import java.io.File
import kotlin.script.experimental.api.ResultValue

/**
 * Result of [DiagramExtractor.extractAny] — a UML or C4 diagram together
 * with whatever supplementary context the renderer needs (for C4 that's the
 * enclosing [C4Model], which holds the elements + relationships that the
 * diagram references by ID).
 */
public sealed class ExtractedDiagram {
    /** A UML diagram from the `diagram { … }` / `classDiagram { … }` family. */
    public data class Uml(
        val diagram: KumlDiagram,
    ) : ExtractedDiagram()

    /** A C4 diagram with its parent [C4Model] (needed by `C4LayoutBridge`). */
    public data class C4(
        val model: C4Model,
        val diagram: C4Diagram,
    ) : ExtractedDiagram()

    /**
     * A SysML 2 diagram with its parent [Sysml2Model].
     *
     * `Sysml2LayoutBridge` needs both: the model holds all definitions/usages,
     * the diagram selects which slice to project. V2.0.4 introduced this for
     * BDD ([BdDiagram]); V2.0.6 widened `diagram` to any [Sysml2Diagram] so
     * IBD ([dev.kuml.sysml2.IbdDiagram]) flows through the same extraction
     * path. The render pipeline branches on the concrete diagram kind.
     */
    public data class Sysml2(
        val model: Sysml2Model,
        val diagram: Sysml2Diagram,
    ) : ExtractedDiagram()
}

/**
 * Extracts a diagram from a Kotlin Scripting evaluation result.
 * Used by the CLI render/validate commands and by the MCP server tools.
 *
 * The original [extract] returns a [KumlDiagram] (UML only) and is retained
 * for backwards compatibility. New callers that need C4 support should use
 * [extractAny], which returns a sealed [ExtractedDiagram] covering both.
 */
object DiagramExtractor {
    /**
     * Extracts a [KumlDiagram] from the script's [ResultValue].
     *
     * The kUML DSL's `diagram(...)` top-level function returns a [KumlDiagram].
     * When used as the **last expression** in a script, the result is
     * [ResultValue.Value] with [ResultValue.Value.value] holding the diagram.
     *
     * When the last statement is an assignment (`val d = diagram(...)`), the
     * result is [ResultValue.Unit]. In that case we look for a `KumlDiagram`
     * property on the script instance via reflection.
     *
     * V1 always takes the **first** diagram found.
     *
     * @param returnValue The `ResultValue` from `EvaluationResult.returnValue`.
     * @param input The script file (used in error messages).
     * @return The extracted [KumlDiagram].
     * @throws ScriptEvaluationException if no diagram can be found.
     */
    fun extract(
        returnValue: ResultValue,
        input: File,
    ): KumlDiagram {
        // Case 1: the script's last expression is the diagram itself
        if (returnValue is ResultValue.Value) {
            val value = returnValue.value
            if (value is KumlDiagram) return value
        }

        // Case 2: script ends with a statement — scan the script instance for a KumlDiagram property
        val instance = returnValue.scriptInstance
        if (instance != null) {
            val diagramProp =
                instance::class
                    .members
                    .filterIsInstance<kotlin.reflect.KProperty1<Any, *>>()
                    .firstOrNull { prop ->
                        try {
                            prop.get(instance) is KumlDiagram
                        } catch (_: Exception) {
                            false
                        }
                    }
            if (diagramProp != null) {
                @Suppress("UNCHECKED_CAST")
                return diagramProp.get(instance) as KumlDiagram
            }
        }

        throw ScriptEvaluationException(
            "Script '${input.name}' did not produce a KumlDiagram. " +
                "Ensure the script ends with a `diagram { }` expression.",
        )
    }

    /**
     * Extracts either a UML [KumlDiagram] or a C4 [C4Diagram] (with its
     * parent [C4Model]) from the script's [ResultValue]. Resolution order:
     *
     *  1. Last expression is a [KumlDiagram] → [ExtractedDiagram.Uml]
     *  2. Last expression is a [C4Model] with at least one diagram
     *     → [ExtractedDiagram.C4] with the first diagram (V1 behaviour
     *     matches the UML path).
     *  3. Script instance has a [KumlDiagram] property → Uml
     *  4. Script instance has a [C4Model] property with diagrams → C4
     *
     * If none of the above match, throws [ScriptEvaluationException].
     *
     * V1.0.1 motivation: pure C4 scripts (`c4Model(name = "...") { … }`)
     * could compile but `kuml render` rejected them because [extract]
     * only knew about [KumlDiagram]. This method closes that gap without
     * breaking the legacy [extract] contract used by validate/generate/MCP.
     */
    fun extractAny(
        returnValue: ResultValue,
        input: File,
    ): ExtractedDiagram {
        // Case 1: last expression is a KumlDiagram
        if (returnValue is ResultValue.Value) {
            val value = returnValue.value
            if (value is KumlDiagram) return ExtractedDiagram.Uml(value)
            if (value is C4Model) {
                value.firstDiagramOrNull()?.let {
                    return ExtractedDiagram.C4(value, it)
                }
            }
            if (value is C4Diagram) {
                // A bare C4Diagram has no parent model — not renderable.
                throw ScriptEvaluationException(
                    "Script '${input.name}' returned a bare C4Diagram. " +
                        "Wrap it inside `c4Model(name = \"…\") { systemContextDiagram(name = \"…\") { … } }` " +
                        "so the renderer has access to the surrounding C4Model.",
                )
            }
            if (value is Sysml2Model) {
                value.firstDiagramOrNull()?.let {
                    return ExtractedDiagram.Sysml2(value, it)
                }
            }
            if (value is BdDiagram) {
                throw ScriptEvaluationException(
                    "Script '${input.name}' returned a bare BdDiagram. " +
                        "Wrap it inside `sysml2Model(\"…\") { bdd(\"…\") { … } }` " +
                        "so the renderer has access to the surrounding Sysml2Model.",
                )
            }
            if (value is IbdDiagram) {
                throw ScriptEvaluationException(
                    "Script '${input.name}' returned a bare IbdDiagram. " +
                        "Wrap it inside `sysml2Model(\"…\") { ibd(\"…\", owner = …) { … } }` " +
                        "so the renderer has access to the surrounding Sysml2Model.",
                )
            }
            if (value is UcDiagram) {
                throw ScriptEvaluationException(
                    "Script '${input.name}' returned a bare UcDiagram. " +
                        "Wrap it inside `sysml2Model(\"…\") { ucDiagram(\"…\") { … } }` " +
                        "so the renderer has access to the surrounding Sysml2Model.",
                )
            }
            if (value is ReqDiagram) {
                throw ScriptEvaluationException(
                    "Script '${input.name}' returned a bare ReqDiagram. " +
                        "Wrap it inside `sysml2Model(\"…\") { reqDiagram(\"…\") { … } }` " +
                        "so the renderer has access to the surrounding Sysml2Model.",
                )
            }
        }

        // Case 2: scan script instance for properties
        val instance = returnValue.scriptInstance
        if (instance != null) {
            val properties =
                instance::class
                    .members
                    .filterIsInstance<kotlin.reflect.KProperty1<Any, *>>()

            // Prefer UML first to preserve historical behaviour.
            properties
                .firstOrNull { prop ->
                    try {
                        prop.get(instance) is KumlDiagram
                    } catch (_: Exception) {
                        false
                    }
                }?.let { prop ->
                    @Suppress("UNCHECKED_CAST")
                    val diagram = prop.get(instance) as KumlDiagram
                    return ExtractedDiagram.Uml(diagram)
                }

            // C4Model property with at least one diagram.
            properties
                .firstOrNull { prop ->
                    try {
                        val v = prop.get(instance)
                        v is C4Model && v.firstDiagramOrNull() != null
                    } catch (_: Exception) {
                        false
                    }
                }?.let { prop ->
                    @Suppress("UNCHECKED_CAST")
                    val model = prop.get(instance) as C4Model
                    return ExtractedDiagram.C4(model, model.firstDiagramOrNull()!!)
                }

            // Sysml2Model property with at least one diagram (BDD or IBD).
            properties
                .firstOrNull { prop ->
                    try {
                        val v = prop.get(instance)
                        v is Sysml2Model && v.firstDiagramOrNull() != null
                    } catch (_: Exception) {
                        false
                    }
                }?.let { prop ->
                    @Suppress("UNCHECKED_CAST")
                    val model = prop.get(instance) as Sysml2Model
                    return ExtractedDiagram.Sysml2(model, model.firstDiagramOrNull()!!)
                }
        }

        throw ScriptEvaluationException(
            "Script '${input.name}' did not produce a renderable diagram. " +
                "End the script with a `classDiagram { … }` (UML), " +
                "a `c4Model(name = \"…\") { systemContextDiagram(name = \"…\") { … } }` (C4), " +
                "or a `sysml2Model(\"…\") { bdd(\"…\") { … } }` (SysML 2 BDD) / " +
                "`sysml2Model(\"…\") { ibd(\"…\", owner = …) { … } }` (SysML 2 IBD) expression.",
        )
    }

    private fun C4Model.firstDiagramOrNull(): C4Diagram? = diagrams.firstOrNull()

    /**
     * V2.0.6: picks the *first* diagram in declaration order, regardless of
     * BDD vs IBD. This preserves the V2.0.4 behaviour for scripts that only
     * declare a BDD (the BDD comes first) while letting IBD-only scripts
     * render through the same path.
     */
    private fun Sysml2Model.firstDiagramOrNull(): Sysml2Diagram? = diagrams.firstOrNull()
}
