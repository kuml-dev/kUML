package dev.kuml.codegen.reverse.java

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import java.nio.file.Path

/**
 * Assembles the [ParserConfiguration] and [CombinedTypeSolver] for one analyze call.
 *
 * Solver priority (highest to lowest):
 * 1. [ReflectionTypeSolver] — JRE + all types visible to the current ClassLoader
 * 2. [JarTypeSolver] per entry in [classpathJars]
 * 3. [JavaParserTypeSolver] per entry in [sourceRoots]
 *
 * Constructed once per [dev.kuml.codegen.reverse.java.JavaSourceReverseEngine.analyze] call.
 */
internal class JavaParserSetup(
    val sourceRoots: List<Path>,
    val classpathJars: List<Path>,
) {
    val combinedTypeSolver: CombinedTypeSolver = buildSolver()

    fun buildParseConfiguration(): ParserConfiguration {
        val solver = JavaSymbolSolver(combinedTypeSolver)
        return ParserConfiguration().apply {
            setSymbolResolver(solver)
            languageLevel = ParserConfiguration.LanguageLevel.JAVA_17
        }
    }

    private fun buildSolver(): CombinedTypeSolver =
        CombinedTypeSolver().apply {
            add(ReflectionTypeSolver(false))
            for (jar in classpathJars) {
                try {
                    add(JarTypeSolver(jar.toFile()))
                } catch (_: Exception) {
                    // Skip unreadable jars silently — diagnostics emitted by the engine
                }
            }
            for (root in sourceRoots) {
                if (root.toFile().isDirectory) {
                    add(JavaParserTypeSolver(root.toFile()))
                }
            }
        }
}
