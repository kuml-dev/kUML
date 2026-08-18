package dev.kuml.style.worker

import kotlinx.serialization.Serializable

/*
 * Newline-delimited JSON IPC protocol between the style-check launcher
 * (`dev.kuml.core.script.style.NamedArgumentStyleCheck`, `:kuml-core:kuml-core-script`)
 * and this worker's [StyleWorkerMain]. The parent writes one
 * [StyleWorkerRequest] JSON line to this process's stdin; the worker writes
 * one [StyleWorkerResponse] JSON line to its stdout and exits.
 *
 * These types are a DELIBERATE duplicate of
 * `dev.kuml.core.script.style.StyleWorkerRequest` / `StyleWorkerResponse` in
 * `:kuml-core:kuml-core-script` — not a shared dependency. The two modules
 * must never depend on each other (see this module's `build.gradle.kts` KDoc
 * for the classpath-collision reason); the JSON *shape* is the contract
 * between them, not a shared Kotlin type. If one side's fields change, the
 * other must be updated to match — there is no compiler to enforce this, only
 * the integration tests in `:kuml-cli`/`:kuml-mcp` that exercise the real
 * child-process round-trip.
 */

/** Parent → worker: "check this script's source for positional dev.kuml.* calls". */
@Serializable
internal data class StyleWorkerRequest(
    val sourcePath: String,
    val binaryRoots: List<String>,
)

/**
 * Worker → parent: the outcome of the style check.
 *
 * Exactly one of ([ok] == true with [findings]) or ([ok] == false with
 * [error]) is populated.
 */
@Serializable
internal data class StyleWorkerResponse(
    val ok: Boolean,
    val findings: List<StyleWorkerFinding> = emptyList(),
    val error: String? = null,
)

@Serializable
internal data class StyleWorkerFinding(
    val line: Int,
    val column: Int,
    val paramName: String,
    val calleeFqName: String,
)
