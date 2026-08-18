package dev.kuml.core.script.style

import kotlinx.serialization.Serializable

/*
 * Newline-delimited JSON IPC protocol between this launcher
 * ([NamedArgumentStyleCheck]) and the `:kuml-style-worker` child JVM
 * (`dev.kuml.style.worker.StyleWorkerMain`). The parent writes one
 * [StyleWorkerRequest] JSON line to the child's stdin; the child writes one
 * [StyleWorkerResponse] JSON line to its stdout and exits.
 *
 * These types are a DELIBERATE duplicate of
 * `dev.kuml.style.worker.StyleWorkerRequest` / `StyleWorkerResponse` in
 * `:kuml-style-worker` — NOT a shared dependency. `:kuml-core:kuml-core-script`
 * must never depend on `:kuml-style-worker` (that module carries the plain,
 * unshaded `org.jetbrains.kotlin:kotlin-compiler`, which is incompatible on
 * the same classpath as `kotlin-compiler-embeddable`, already pulled in here
 * via `kotlin-scripting-jvm-host` — see `:kuml-style-worker/build.gradle.kts`
 * for the full explanation), and `:kuml-style-worker` must never depend on
 * this module either. The JSON shape is the contract, not a shared Kotlin
 * type.
 */

/** Launcher → worker: "check this script's source for positional dev.kuml.* calls". */
@Serializable
internal data class StyleWorkerRequest(
    val sourcePath: String,
    val binaryRoots: List<String>,
)

/**
 * Worker → launcher: the outcome of the style check.
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
