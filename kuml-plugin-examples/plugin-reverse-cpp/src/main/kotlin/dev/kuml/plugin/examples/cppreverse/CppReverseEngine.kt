package dev.kuml.plugin.examples.cppreverse

import dev.kuml.codegen.reverse.KumlReverseEngine
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
 * Handwritten recursive-descent C++ header → UML reverse engine (V3.1.39).
 *
 * Supported file extensions: `.hpp`, `.h`, `.hxx`, `.hh`, `.h++`, `.cpp`, `.cc`, `.cxx`.
 *
 * Limitations:
 * - Template meta-programming is detected but only parsed structurally (REV-CPP-001 warning).
 * - Preprocessor macros and directives are stripped before parsing.
 * - Eclipse CDT Core is NOT used (OSGi/P2 bundle — not consumable from Maven Central).
 *
 * Safety limits:
 * - Max file size: [MAX_FILE_BYTES] (10 MB).
 * - Max files per run: [MAX_FILES] (2000).
 * - Max namespace nesting depth: 256 levels (REV-CPP-002 warning if exceeded).
 */
public class CppReverseEngine : KumlReverseEngine {
    override val id: String = "cpp"
    override val description: String =
        "Handwritten recursive-descent C++ header → UML reverse engine (V3.1.39)."

    private val typeMapper = CppTypeMapper()
    private val umlMapper = CppUmlMapper(typeMapper)

    override suspend fun analyze(request: ReverseRequest): ReverseResult =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val fileAsts = mutableListOf<CppFileAst>()
            val allDiagnostics = mutableListOf<dev.kuml.codegen.reverse.ReverseDiagnostic>()
            var filesAnalysed = 0

            outer@ for (root in request.sourceRoots) {
                val rootFile = root.toFile()
                val rootCanonical = rootFile.canonicalPath
                val cppFiles =
                    rootFile
                        .walkTopDown()
                        .onEnter { dir ->
                            // Reject symlinks that point outside the source root to prevent
                            // path-traversal attacks (CVE-class: symlink escape).
                            dir == rootFile ||
                                dir.canonicalPath.startsWith(rootCanonical + File.separator)
                        }.filter { it.isFile && it.extension in CPP_EXTENSIONS }
                for (file in cppFiles) {
                    if (filesAnalysed >= MAX_FILES) {
                        allDiagnostics +=
                            dev.kuml.codegen.reverse.ReverseDiagnostic(
                                severity = dev.kuml.codegen.reverse.ReverseDiagnostic.Severity.WARN,
                                code = "REV-CPP-003",
                                message =
                                    "File cap ($MAX_FILES) reached — " +
                                        "skipping remaining files.",
                            )
                        break@outer
                    }
                    // Use a root-relative path in diagnostics to avoid leaking absolute
                    // filesystem layout in server-side deployments.
                    val relPath = file.relativeTo(rootFile).path
                    if (file.length() > MAX_FILE_BYTES) {
                        allDiagnostics +=
                            dev.kuml.codegen.reverse.ReverseDiagnostic(
                                severity = dev.kuml.codegen.reverse.ReverseDiagnostic.Severity.WARN,
                                code = "REV-CPP-004",
                                message =
                                    "Skipping oversized file " +
                                        "'$relPath' (${file.length()} bytes > $MAX_FILE_BYTES).",
                            )
                        continue
                    }
                    try {
                        val ast = parseCppSource(file.readText())
                        fileAsts += ast
                        allDiagnostics += ast.diagnostics
                        filesAnalysed++
                    } catch (e: StackOverflowError) {
                        allDiagnostics +=
                            dev.kuml.codegen.reverse.ReverseDiagnostic(
                                severity = dev.kuml.codegen.reverse.ReverseDiagnostic.Severity.ERROR,
                                code = "REV-CPP-005",
                                message =
                                    "StackOverflowError while parsing " +
                                        "'$relPath' — skipping file.",
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

    /** Parse a C++ source string into a [CppFileAst]. Exposed for testing. */
    internal fun parseCppSource(source: String): CppFileAst {
        val tokens = CppLexer(source).tokenize()
        return CppReverseParser(tokens).parse()
    }

    /** Map a C++ type string to a UML-friendly type name. Exposed for testing. */
    internal fun mapCppType(t: String): String = typeMapper.map(t)

    private companion object {
        const val MAX_FILE_BYTES: Long = 10L * 1024 * 1024
        const val MAX_FILES: Int = 2_000
        val CPP_EXTENSIONS: Set<String> = setOf("hpp", "h", "hxx", "hh", "h++", "cpp", "cc", "cxx")
    }
}
