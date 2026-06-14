package dev.kuml.codegen.reverse.kotlin

import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * Test helper: writes Kotlin source strings to a temp dir and runs [KotlinSourceReverseEngine].
 */
internal object TestSupport {
    /** Map of relPath (e.g. "Foo.kt") → source code. */
    fun runEngine(
        sources: Map<String, String>,
        modelName: String = "Test",
    ): ReverseResult {
        val tmp = Files.createTempDirectory("kuml-reverse-kt-test-")
        sources.forEach { (relPath, code) ->
            val p = tmp.resolve(relPath)
            Files.createDirectories(p.parent)
            Files.writeString(p, code)
        }
        val request =
            ReverseRequest(
                sourceRoots = listOf(tmp),
                targetModelName = modelName,
            )
        return runBlocking { KotlinSourceReverseEngine().analyze(request) }
    }

    fun success(result: ReverseResult): ReverseResult.Success =
        result as? ReverseResult.Success
            ?: error("Expected ReverseResult.Success but got: $result")
}
