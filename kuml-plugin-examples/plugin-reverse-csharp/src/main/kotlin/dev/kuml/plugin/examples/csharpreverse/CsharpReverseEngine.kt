package dev.kuml.plugin.examples.csharpreverse

import dev.kuml.codegen.reverse.KumlReverseEngine
import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handwritten recursive-descent C# → UML reverse engine (V3.1.40).
 *
 * Supported file extension: `.cs`.
 *
 * Note: ANTLR4 was evaluated for this implementation but rejected — no reliable
 * ANTLR4 C# grammar artifact is available on Maven Central. The handwritten
 * structural parser (Option B from the spec) is used instead, following the
 * pattern established for C++ in V3.1.39.
 *
 * Limitations:
 * - Method bodies, LINQ, lambdas, and complex expressions are out of scope.
 * - Generic constraints (`where T : class`) are skipped structurally.
 * - Preprocessor directives (#if, #region, #pragma) are stripped before parsing.
 *
 * Safety limits:
 * - Max file size: [MAX_FILE_BYTES] (10 MB).
 * - Max files per run: [MAX_FILES] (2000).
 * - Max namespace nesting depth: 256 levels (REV-CS-002 warning if exceeded).
 * - Symlink-escape guard prevents path traversal outside the source root.
 */
public class CsharpReverseEngine : KumlReverseEngine {
    override val id: String = "csharp"
    override val description: String =
        "Handwritten recursive-descent C# → UML reverse engine (V3.1.40)."

    private val typeMapper = CsharpTypeMapper()
    private val umlMapper = CsharpUmlMapper(typeMapper)

    override suspend fun analyze(request: ReverseRequest): ReverseResult =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val fileAsts = mutableListOf<CsharpFileAst>()
            val allDiagnostics = mutableListOf<ReverseDiagnostic>()
            var filesAnalysed = 0

            outer@ for (root in request.sourceRoots) {
                val rootFile = root.toFile()
                val rootCanonical = rootFile.canonicalPath
                val csFiles =
                    rootFile
                        .walkTopDown()
                        .onEnter { dir ->
                            // Symlink-escape guard: reject symlinks pointing outside the source root
                            // to prevent path-traversal attacks (CVE-class: symlink escape).
                            dir == rootFile ||
                                dir.canonicalPath.startsWith(rootCanonical + File.separator)
                        }.filter { it.isFile && it.extension in CS_EXTENSIONS }
                for (file in csFiles) {
                    if (filesAnalysed >= MAX_FILES) {
                        allDiagnostics +=
                            ReverseDiagnostic(
                                severity = ReverseDiagnostic.Severity.WARN,
                                code = "REV-CS-003",
                                message = "File cap ($MAX_FILES) reached — skipping remaining files.",
                            )
                        break@outer
                    }
                    val relPath = file.relativeTo(rootFile).path
                    if (file.length() > MAX_FILE_BYTES) {
                        allDiagnostics +=
                            ReverseDiagnostic(
                                severity = ReverseDiagnostic.Severity.WARN,
                                code = "REV-CS-004",
                                message =
                                    "Skipping oversized file '$relPath' (${file.length()} bytes > $MAX_FILE_BYTES).",
                            )
                        continue
                    }
                    try {
                        val ast = parseCsharpSource(file.readText())
                        fileAsts += ast
                        allDiagnostics += ast.diagnostics
                        filesAnalysed++
                    } catch (e: StackOverflowError) {
                        allDiagnostics +=
                            ReverseDiagnostic(
                                severity = ReverseDiagnostic.Severity.ERROR,
                                code = "REV-CS-005",
                                message = "StackOverflowError while parsing '$relPath' — skipping file.",
                            )
                    }
                }
            }

            val (elements, mapperDiagnostics) = umlMapper.buildElements(fileAsts = fileAsts)
            allDiagnostics += mapperDiagnostics

            val diagram =
                KumlDiagram(
                    id = request.targetModelName,
                    name = request.targetModelName,
                    type = DiagramType.CLASS,
                    elements = elements,
                )
            val model =
                KumlModel(
                    root = diagram,
                    language = ModelingLanguage.UML,
                    level = ModelLevel.PIM,
                    name = request.targetModelName,
                )
            ReverseResult.Success(
                model = model,
                filesAnalysed = filesAnalysed,
                elapsedMs = System.currentTimeMillis() - startMs,
                diagnostics = allDiagnostics,
            )
        }

    /** Parse a C# source string into a [CsharpFileAst]. Exposed for testing. */
    internal fun parseCsharpSource(source: String): CsharpFileAst {
        val tokens = CsharpLexer(source).tokenize()
        return CsharpReverseParser(tokens).parse()
    }

    /** Map a C# type string to a UML-friendly type name. Exposed for testing. */
    internal fun mapCsharpType(t: String): String = typeMapper.map(t)

    private companion object {
        const val MAX_FILE_BYTES: Long = 10L * 1024 * 1024
        const val MAX_FILES: Int = 2_000
        val CS_EXTENSIONS: Set<String> = setOf("cs")
    }
}
