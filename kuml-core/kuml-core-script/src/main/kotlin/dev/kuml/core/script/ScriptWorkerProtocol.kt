package dev.kuml.core.script

import kotlinx.serialization.Serializable

/*
 * Newline-delimited JSON IPC protocol between the MCP server (parent) and a
 * script-evaluation child JVM (ScriptWorkerMain).
 *
 * This is deliberately NOT MCP/JSON-RPC — the child does exactly one thing
 * (evaluate a script) so a minimal request/response pair is enough. The parent
 * writes one WorkerRequest JSON line to the child's stdin; the child writes one
 * WorkerResponse JSON line to its stdout and exits.
 *
 * Both messages are single lines: the payloads never contain a raw newline
 * because they are JSON-encoded (script source is encoded as a JSON string, so
 * its newlines become \n escapes). The reader on each side reads exactly one
 * line.
 *
 * V0.23.3 — Welle 2.
 */

/** Parent → child: "evaluate this script". */
@Serializable
internal data class WorkerRequest(
    val source: String,
    val fileName: String,
)

/**
 * Child → parent: the outcome of evaluation.
 *
 * Exactly one of ([ok] == true with [envelope]) or ([ok] == false with
 * [failureKind] + [message]) is populated.
 */
@Serializable
internal data class WorkerResponse(
    val ok: Boolean,
    /** On success: the [ExtractedDiagramCodec] envelope JSON. */
    val envelope: String? = null,
    /** On failure: the [FailureKind] name. */
    val failureKind: String? = null,
    /** On failure: a sanitised human-readable message. */
    val message: String? = null,
)
