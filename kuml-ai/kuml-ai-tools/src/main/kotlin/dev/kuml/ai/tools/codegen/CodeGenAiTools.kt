package dev.kuml.ai.tools.codegen

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.ai.tools.internal.EnumCoercion
import dev.kuml.ai.tools.internal.IdHelpers
import dev.kuml.ai.tools.uml.UmlPatchOps
import dev.kuml.codegen.api.CodeGenRegistry
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Agent tools for code generation from kUML models.
 *
 * Provides three tools:
 *  - [addJpaEntity]: adds a UML class with `entity` stereotype (JPA mapping).
 *  - [addSpringBean]: adds a UML class with Spring stereotype + dependency associations.
 *  - [generateCode]: invokes [CodeGenRegistry] to generate code into a target directory.
 *
 * All mutations use [AgentEditingContext.applyPatch] — same pattern as [dev.kuml.ai.tools.uml.UmlEditingTools].
 * [generateCode] does NOT mutate the model — it reads the current working state and writes files.
 *
 * V3.1.20.
 */
@LLMDescription(
    "Tools for code generation from kUML UML models. Supports JPA entity generation, " +
        "Spring bean scaffolding, and full code generation via registered CodeGen plugins.",
)
public class CodeGenAiTools(
    private val ctx: AgentEditingContext,
) : ToolSet {
    // ── add_jpa_entity ────────────────────────────────────────────────────────

    @Tool(customName = "add_jpa_entity")
    @LLMDescription(
        "Adds a UML class with 'entity' stereotype for JPA mapping. " +
            "The class is linked to the given database table name via a payload annotation. " +
            "Returns the assigned UML element id.",
    )
    public suspend fun addJpaEntity(
        @LLMDescription("Class name in PascalCase, e.g. 'OrderItem'.") className: String,
        @LLMDescription("Database table name, e.g. 'order_items'.") tableName: String,
        @LLMDescription(
            "Optional field definitions. Each field becomes a UML attribute.",
        ) fields: List<FieldSpec> = emptyList(),
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return PatchApplyResult.Failure("Context is not a UML model")

        val takenIds = uml.elements.map { it.id }.toSet()
        val classId = IdHelpers.uniqueId(className, takenIds)

        val attrTaken = (takenIds + classId).toMutableSet()
        val umlAttrs =
            fields.map { spec ->
                val attrId = IdHelpers.uniqueId(spec.name, attrTaken, "attr")
                attrTaken += attrId
                UmlProperty(
                    id = attrId,
                    name = spec.name,
                    type = UmlTypeRef(spec.type),
                    visibility = EnumCoercion.toVisibility(spec.visibility) ?: Visibility.PRIVATE,
                    defaultValue = spec.defaultValue,
                )
            }

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                elementKind = "uml.class",
                elementId = classId,
                name = className,
                payload =
                    buildMap {
                        put("stereotype", "entity")
                        put("jpa.table", tableName)
                    },
            )

        return ctx.applyPatch(patch) { m ->
            val u = m as AnyKumlModel.Uml
            UmlPatchOps.addClass(u, classId, className, "entity", false, umlAttrs)
        }
    }

    // ── add_spring_bean ───────────────────────────────────────────────────────

    @Tool(customName = "add_spring_bean")
    @LLMDescription(
        "Adds a Spring-managed bean class with the given stereotype " +
            "('service', 'repository', 'controller', or 'component') and creates " +
            "UML dependency associations to the named dependency classes. " +
            "Returns the bean element id. Unknown dependency names produce Failure.",
    )
    public suspend fun addSpringBean(
        @LLMDescription("Class name in PascalCase, e.g. 'OrderService'.") className: String,
        @LLMDescription(
            "Spring bean stereotype: 'service', 'repository', 'controller', or 'component'.",
        ) beanType: String,
        @LLMDescription(
            "Names or ids of existing classifier elements this bean depends on. " +
                "Each entry becomes a UML dependency (use) association.",
        ) dependencies: List<String> = emptyList(),
    ): PatchApplyResult {
        val normalizedType = beanType.lowercase().trim()
        val validTypes = setOf("service", "repository", "controller", "component")
        if (normalizedType !in validTypes) {
            return PatchApplyResult.Failure(
                reason = "Invalid beanType '$beanType'. Must be one of: ${validTypes.joinToString()}.",
                hint = "Common values: 'service' for business logic, 'repository' for data access.",
            )
        }

        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return PatchApplyResult.Failure("Context is not a UML model")

        // Validate all dependencies before touching the model
        val resolvedDeps =
            dependencies.map { depName ->
                UmlPatchOps.resolveClassifier(uml, depName)
                    ?: return PatchApplyResult.Failure(
                        reason = "Dependency '$depName' not found in model",
                        hint = "Use list_elements to discover available classifier ids",
                    )
            }

        val takenIds = uml.elements.map { it.id }.toSet()
        val beanId = IdHelpers.uniqueId(className, takenIds)

        // One AddElement patch for the bean class itself
        val beanPatch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                elementKind = "uml.class",
                elementId = beanId,
                name = className,
                payload = mapOf("stereotype" to normalizedType),
            )

        var result =
            ctx.applyPatch(beanPatch) { m ->
                val u = m as AnyKumlModel.Uml
                UmlPatchOps.addClass(u, beanId, className, normalizedType, false)
            }
        if (result is PatchApplyResult.Failure) return result

        // One AddRelationship patch per dependency
        for (dep in resolvedDeps) {
            val assocId = IdHelpers.uniqueId("${beanId}_uses_${dep.id}", emptySet())
            val assocPatch =
                ModelPatch.AddRelationship(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = ctx.currentDiagramId ?: uml.diagramId,
                    relationshipKind = "uml.association",
                    relationshipId = assocId,
                    sourceId = beanId,
                    targetId = dep.id,
                    payload = mapOf("name" to "uses"),
                )
            result =
                ctx.applyPatch(assocPatch) { m ->
                    val u = m as AnyKumlModel.Uml
                    UmlPatchOps.addAssociation(u, assocId, beanId, dep.id, "uses", Multiplicity(1, 1), Multiplicity(1, 1))
                }
            if (result is PatchApplyResult.Failure) return result
        }

        return result
    }

    // ── generate_code ─────────────────────────────────────────────────────────

    @Tool(customName = "generate_code")
    @LLMDescription(
        "Generates code from the current UML model using the specified target language plugin. " +
            "Writes files into the given output directory (must be within the allowed sandbox root). " +
            "Returns a list of generated file paths on success. " +
            "Currently supports UML models only; C4/SysML 2 return Failure.",
    )
    public suspend fun generateCode(
        @LLMDescription(
            "Code generation target language id, e.g. 'kotlin', 'java', or 'sql'.",
        ) targetLanguage: String,
        @LLMDescription(
            "Output directory path. Must not traverse outside the project root (no '..' segments).",
        ) outputPath: String,
    ): CodeGenResult {
        // ── Path-traversal guard ───────────────────────────────────────────────
        val normalizedPath: Path =
            try {
                Paths.get(outputPath).normalize()
            } catch (e: Exception) {
                return CodeGenResult.Failure("Invalid output path: ${e.message}")
            }
        if (normalizedPath.toString().contains("..")) {
            return CodeGenResult.Failure(
                "Output path must not contain '..' segments — potential path traversal rejected.",
            )
        }
        // Reject absolute paths pointing outside common safe roots to prevent accidental writes.
        if (normalizedPath.isAbsolute) {
            val pathStr = normalizedPath.toString()
            val safe = SAFE_ABSOLUTE_PREFIXES.any { pathStr.startsWith(it) }
            if (!safe) {
                return CodeGenResult.Failure(
                    "Absolute output path '$outputPath' is not within an allowed directory. " +
                        "Use a relative path or a path under the project's output directory.",
                )
            }
        }

        // ── Model extraction ───────────────────────────────────────────────────
        val model = ctx.resolveModel()
        val kumlDiagram: dev.kuml.core.model.KumlDiagram =
            when (model) {
                is AnyKumlModel.Uml ->
                    model.toKumlModel().root as? dev.kuml.core.model.KumlDiagram
                        ?: return CodeGenResult.Failure("Internal error: UML model root is not a KumlDiagram")
                is AnyKumlModel.C4 -> return CodeGenResult.Failure(
                    reason = "Code generation from C4 models is not yet supported. Use a UML model.",
                )
                is AnyKumlModel.Sysml2 -> return CodeGenResult.Failure(
                    reason = "Code generation from SysML 2 models is not yet supported. Use a UML model.",
                )
            }

        // ── Registry lookup ───────────────────────────────────────────────────
        if (CodeGenRegistry.names().isEmpty()) {
            CodeGenRegistry.loadFromClasspath()
        }
        val generator =
            CodeGenRegistry.get(targetLanguage)
                ?: return CodeGenResult.Failure(
                    reason =
                        "Unknown code generator: '$targetLanguage'. " +
                            "Registered generators: ${CodeGenRegistry.names()}",
                )

        // ── Generation ────────────────────────────────────────────────────────
        val outputDir = normalizedPath.toFile()
        return try {
            val generated = generator.generate(kumlDiagram, outputDir, emptyMap())
            CodeGenResult.Success(
                generatedFiles = generated.map { it.absolutePath },
                generatorId = generator.id,
            )
        } catch (e: dev.kuml.codegen.api.CodeGenerationException) {
            CodeGenResult.Failure(reason = "Code generation failed: ${e.message}")
        } catch (e: Exception) {
            CodeGenResult.Failure(reason = "Unexpected error during code generation: ${e.message}")
        }
    }

    // ── Data types ────────────────────────────────────────────────────────────

    @Serializable
    public data class FieldSpec(
        @property:LLMDescription("Field name in camelCase.") val name: String,
        @property:LLMDescription("Field type, e.g. String, Long, LocalDate.") val type: String,
        @property:LLMDescription("UML visibility: PUBLIC, PROTECTED, PRIVATE, PACKAGE.") val visibility: String? = null,
        @property:LLMDescription("Optional default-value literal.") val defaultValue: String? = null,
    )

    @Serializable
    public sealed interface CodeGenResult {
        @Serializable
        public data class Success(
            val generatedFiles: List<String>,
            val generatorId: String,
        ) : CodeGenResult

        @Serializable
        public data class Failure(
            val reason: String,
        ) : CodeGenResult
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    private companion object {
        /** Absolute path prefixes considered safe for code generation output. */
        private val SAFE_ABSOLUTE_PREFIXES: List<String> =
            buildList {
                add(System.getProperty("java.io.tmpdir") ?: "/tmp")
                add(System.getProperty("user.home") ?: "")
                // Allow paths under typical build output directories
                add("/tmp")
            }.filter { it.isNotEmpty() }
    }
}
