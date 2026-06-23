package dev.kuml.plugin.examples.tsreverse

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

public class TypeScriptReverseEngine : KumlReverseEngine {
    override val id: String = "typescript"
    override val description: String = "Handwritten recursive-descent TypeScript → UML reverse engine (V3.1.9)."

    private val typeMapper = TsTypeMapper()
    private val modelBuilder = TsModelBuilder(typeMapper)

    override suspend fun analyze(request: ReverseRequest): ReverseResult =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val fileAsts = mutableListOf<TsFileAst>()
            var filesAnalysed = 0

            outer@ for (root in request.sourceRoots) {
                val tsFiles =
                    root
                        .toFile()
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "ts" && !it.name.endsWith(".d.ts") }
                for (file in tsFiles) {
                    if (filesAnalysed >= MAX_FILES) {
                        System.err.println(
                            "[TypeScriptReverseEngine] File cap ($MAX_FILES) reached — " +
                                "skipping remaining files.",
                        )
                        break@outer
                    }
                    if (file.length() > MAX_FILE_BYTES) {
                        System.err.println(
                            "[TypeScriptReverseEngine] Skipping oversized file " +
                                "'${file.path}' (${file.length()} bytes > $MAX_FILE_BYTES).",
                        )
                        continue
                    }
                    try {
                        fileAsts += parseTypeScriptSource(file.readText())
                        filesAnalysed++
                    } catch (e: StackOverflowError) {
                        // Belt-and-suspenders: TsParser has a typeRefDepth guard that should prevent
                        // this, but a crafted file could still exhaust the JVM stack through other
                        // call paths. Log and continue rather than crashing the entire analysis run.
                        System.err.println(
                            "[TypeScriptReverseEngine] StackOverflowError while parsing " +
                                "'${file.path}' — skipping file. The file likely contains " +
                                "pathologically deep nesting.",
                        )
                    }
                }
            }

            val elements = modelBuilder.buildElements(fileAsts = fileAsts)

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
            )
        }

    internal fun parseTypeScriptSource(source: String): TsFileAst {
        val tokens = TsLexer(source).tokenize()
        return TsParser(tokens).parse()
    }

    internal fun mapTsType(tsType: String): String = typeMapper.map(tsType)

    private companion object {
        /** Maximum number of bytes read from a single `.ts` file (10 MB). */
        const val MAX_FILE_BYTES: Long = 10L * 1024 * 1024

        /** Maximum total number of `.ts` files visited per analysis run. */
        const val MAX_FILES: Int = 2_000
    }
}
