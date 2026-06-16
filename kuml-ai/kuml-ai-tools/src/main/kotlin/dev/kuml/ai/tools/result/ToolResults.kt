package dev.kuml.ai.tools.result

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

@Serializable
public sealed interface RemoveResult {
    @Serializable
    public data class Success(
        val removedId: String,
        val patchId: String,
    ) : RemoveResult

    @Serializable
    public data class Failure(
        val reason: String,
    ) : RemoveResult
}

@Serializable
public sealed interface RenameResult {
    @Serializable
    public data class Success(
        val elementId: String,
        val oldName: String,
        val newName: String,
        val patchId: String,
    ) : RenameResult

    @Serializable
    public data class Failure(
        val reason: String,
    ) : RenameResult
}

@Serializable
public data class ElementSummary(
    @property:LLMDescription("Stable element id (UUID-shape).") val id: String,
    @property:LLMDescription("Human-readable name.") val name: String,
    @property:LLMDescription(
        "Element kind code: uml.class, uml.interface, c4.person, sysml2.partdef, …",
    ) val kind: String,
    @property:LLMDescription("Optional parent container id.") val parentId: String? = null,
)

@Serializable
public data class ElementDetails(
    val id: String,
    val name: String,
    val kind: String,
    val attributes: List<AttributeView> = emptyList(),
    val operations: List<OperationView> = emptyList(),
    val incomingRelationshipIds: List<String> = emptyList(),
    val outgoingRelationshipIds: List<String> = emptyList(),
    val stereotypes: List<String> = emptyList(),
) {
    @Serializable
    public data class AttributeView(
        val id: String,
        val name: String,
        val type: String,
        val visibility: String,
    )

    @Serializable
    public data class OperationView(
        val id: String,
        val signature: String,
        val visibility: String,
    )
}

@Serializable
public data class UnusedReport(
    val unusedElementIds: List<String>,
    val rationale: String,
)

@Serializable
public sealed interface RenderResult {
    @Serializable
    public data class Svg(
        val filePath: String,
        val summary: String,
    ) : RenderResult

    @Serializable
    public data class Png(
        val filePath: String,
        val widthPx: Int,
        val heightPx: Int,
    ) : RenderResult

    @Serializable
    public data class Failure(
        val reason: String,
    ) : RenderResult
}

@Serializable
public sealed interface ValidateResult {
    @Serializable
    public data class Ok(
        val checkedElements: Int,
    ) : ValidateResult

    @Serializable
    public data class Issues(
        val errors: List<String>,
        val warnings: List<String>,
    ) : ValidateResult
}

@Serializable
public sealed interface SimulationResult {
    @Serializable
    public data class Trace(
        val finalStates: List<String>,
        /** Compact textual step descriptions; full trace via filePath. */
        val steps: List<String>,
        /** OTLP-JSON path written to temp. */
        val traceFilePath: String,
    ) : SimulationResult

    @Serializable
    public data class Failure(
        val reason: String,
    ) : SimulationResult
}
