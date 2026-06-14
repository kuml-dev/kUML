package dev.kuml.codegen.reverse.java

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import dev.kuml.codegen.reverse.KumlReverseEngine
import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.codegen.reverse.java.mapping.JavaAssociationDetector
import dev.kuml.codegen.reverse.java.mapping.JavaClassMapper
import dev.kuml.codegen.reverse.java.mapping.JavaEnumerationMapper
import dev.kuml.codegen.reverse.java.mapping.JavaGeneralizationMapper
import dev.kuml.codegen.reverse.java.mapping.JavaOperationMapper
import dev.kuml.codegen.reverse.java.mapping.JavaTypeResolver
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * JavaParser-based implementation of [KumlReverseEngine] for `*.java` sources.
 *
 * Pipeline:
 * 1. Glob-collect `.java` files matching [ReverseRequest.includeGlobs] (default `**&#47;*.java`).
 * 2. Configure [JavaParserSetup] with SymbolSolver (Reflection → Jars → SourceRoots).
 * 3. Parse files in parallel via [Dispatchers.IO] coroutines.
 * 4. Pass 1: collect all classifiers (classes, interfaces, enums) — build id pool.
 * 5. Pass 2: map features (attributes, operations) + relationships (generalization,
 *    realization, association).
 * 6. Assemble flat [KumlDiagram] (elements list) → [KumlModel].
 *
 * JVM-only: never include in GraalVM Native Image (JavaParser uses reflection internally).
 */
public class JavaSourceReverseEngine : KumlReverseEngine {
    override val id: String = "java"
    override val description: String = "JavaParser-based Java source → UML reverse engine"

    override suspend fun analyze(request: ReverseRequest): ReverseResult =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val diagnostics = mutableListOf<ReverseDiagnostic>()

            val files = collectFiles(request)
            if (files.isEmpty()) {
                return@withContext ReverseResult.Failure(
                    listOf(
                        ReverseDiagnostic(
                            severity = ReverseDiagnostic.Severity.ERROR,
                            code = "REV-CORE-001",
                            message = "No Java source files found in source roots: ${request.sourceRoots}",
                        ),
                    ),
                )
            }

            val setup = JavaParserSetup(request.sourceRoots, request.classpathJars)
            val parseConfig = setup.buildParseConfiguration()

            // Parse all files in parallel — each coroutine creates its own JavaParser
            // because JavaParser instances are not thread-safe.
            val parseResults =
                files
                    .map { file ->
                        async {
                            val result = JavaParser(parseConfig).parse(file.toFile())
                            result.result.orElse(null) to file
                        }
                    }.awaitAll()

            // Collect parsed CUs (skip files that failed to parse)
            val compilationUnits =
                parseResults.mapNotNull { (cu, file) ->
                    if (cu == null) {
                        diagnostics +=
                            ReverseDiagnostic(
                                severity = ReverseDiagnostic.Severity.WARN,
                                code = "REV-CORE-002",
                                message = "Failed to parse file: $file",
                                file = file.toString(),
                            )
                        null
                    } else {
                        cu to file
                    }
                }

            // Pass 1: collect all user-defined type names (FQN pool for association detection)
            val userTypeNames = mutableSetOf<String>()
            for ((cu, _) in compilationUnits) {
                val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
                cu.findAll(ClassOrInterfaceDeclaration::class.java).forEach { decl ->
                    val fqn = if (pkg.isNotBlank()) "$pkg.${decl.nameAsString}" else decl.nameAsString
                    userTypeNames += fqn
                }
                cu.findAll(EnumDeclaration::class.java).forEach { decl ->
                    val fqn = if (pkg.isNotBlank()) "$pkg.${decl.nameAsString}" else decl.nameAsString
                    userTypeNames += fqn
                }
            }

            val resolver = JavaTypeResolver(setup.combinedTypeSolver, userTypeNames)
            val associationDetector = JavaAssociationDetector(resolver)

            // Pass 2: map all classifiers and relationships
            val classes = mutableListOf<UmlClass>()
            val interfaces = mutableListOf<UmlInterface>()
            val enumerations = mutableListOf<UmlEnumeration>()
            val generalizations = mutableListOf<UmlGeneralization>()
            val realizations = mutableListOf<UmlInterfaceRealization>()
            val associations = mutableListOf<UmlAssociation>()

            for ((cu, file) in compilationUnits) {
                val fileName = file.fileName.toString()
                processCu(
                    cu = cu,
                    fileName = fileName,
                    associationDetector = associationDetector,
                    diagnostics = diagnostics,
                    classes = classes,
                    interfaces = interfaces,
                    enumerations = enumerations,
                    generalizations = generalizations,
                    realizations = realizations,
                    associations = associations,
                )
            }

            // Assemble flat element list — deterministic order as per Plan § 10.4
            val elements: List<UmlElement> =
                buildList {
                    addAll(enumerations.sortedBy { it.name })
                    addAll(interfaces.sortedBy { it.name })
                    addAll(classes.sortedBy { it.name })
                    // Relationships in deterministic order
                    addAll(
                        generalizations.sortedWith(
                            compareBy({ it.specificId }, { it.generalId }),
                        ),
                    )
                    addAll(
                        realizations.sortedWith(
                            compareBy({ it.implementingId }, { it.interfaceId }),
                        ),
                    )
                    addAll(
                        associations.sortedWith(
                            compareBy({ it.ends[0].typeId }, { it.ends[1].typeId }),
                        ),
                    )
                }

            val rootDiagram =
                KumlDiagram(
                    id = request.targetModelName,
                    name = request.targetModelName,
                    type = DiagramType.CLASS,
                    elements = elements,
                )

            val model =
                KumlModel(
                    root = rootDiagram,
                    language = ModelingLanguage.UML,
                    level = ModelLevel.PIM,
                    name = request.targetModelName,
                )

            ReverseResult.Success(
                model = model,
                diagnostics = diagnostics,
                filesAnalysed = compilationUnits.size,
                elapsedMs = System.currentTimeMillis() - startMs,
            )
        }

    @Suppress("LongParameterList")
    private fun processCu(
        cu: CompilationUnit,
        fileName: String,
        associationDetector: JavaAssociationDetector,
        diagnostics: MutableList<ReverseDiagnostic>,
        classes: MutableList<UmlClass>,
        interfaces: MutableList<UmlInterface>,
        enumerations: MutableList<UmlEnumeration>,
        generalizations: MutableList<UmlGeneralization>,
        realizations: MutableList<UmlInterfaceRealization>,
        associations: MutableList<UmlAssociation>,
    ) {
        val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")

        // Enumerations
        cu.findAll(EnumDeclaration::class.java).forEach { enumDecl ->
            enumerations += JavaEnumerationMapper.map(enumDecl, packageName)
        }

        // Classes and interfaces (top-level and nested)
        cu.findAll(ClassOrInterfaceDeclaration::class.java).forEach { decl ->
            // Skip anonymous classes (name would be empty string)
            if (decl.nameAsString.isEmpty()) {
                diagnostics +=
                    ReverseDiagnostic(
                        severity = ReverseDiagnostic.Severity.INFO,
                        code = "REV-J-011",
                        message = "Anonymous inner class skipped — not represented in UML model.",
                        file = fileName,
                        line = decl.begin.map { it.line }.orElse(null),
                    )
                return@forEach
            }

            // Determine enclosing class (if nested)
            val enclosingName: String? =
                decl.parentNode
                    .flatMap { parent ->
                        if (parent is ClassOrInterfaceDeclaration) {
                            java.util.Optional.of(parent.nameAsString)
                        } else {
                            java.util.Optional.empty()
                        }
                    }.orElse(null)

            val mapped = JavaClassMapper.map(decl, packageName, enclosingName)
            val classId = mapped.id

            // Emit template diagnostic for generic types
            if (decl.typeParameters.isNonEmpty) {
                diagnostics +=
                    ReverseDiagnostic(
                        severity = ReverseDiagnostic.Severity.INFO,
                        code = "REV-J-010",
                        message =
                            "Generic type parameters on '${decl.nameAsString}' mapped as raw type " +
                                "(stereotypes + metadata); UML template parameters not modeled in V1 metamodel.",
                        file = fileName,
                        line = decl.begin.map { it.line }.orElse(null),
                    )
            }

            val attributes = mutableListOf<UmlProperty>()
            val operations = mutableListOf<UmlOperation>()

            decl.fields.forEach { field ->
                field.variables.forEach { variable ->
                    val classification =
                        associationDetector.classify(field, variable, classId, fileName)
                    when (classification) {
                        is JavaAssociationDetector.FieldClassification.AsAssociation ->
                            associations += classification.association
                        is JavaAssociationDetector.FieldClassification.AsProperty -> {
                            attributes += classification.property
                            classification.diagnostic?.let { diagnostics += it }
                        }
                        is JavaAssociationDetector.FieldClassification.Skipped ->
                            diagnostics += classification.diagnostic
                    }
                }
            }

            // Map methods
            decl.methods.forEach { method ->
                operations += JavaOperationMapper.map(method, classId)
            }

            // Generalization and realization
            val genResult = JavaGeneralizationMapper.map(decl, classId, "$classId.rel")
            generalizations += genResult.generalizations
            realizations += genResult.realizations

            if (mapped.umlClass != null) {
                classes +=
                    mapped.umlClass.copy(
                        attributes = attributes.sortedBy { it.name },
                        operations = operations.sortedBy { it.name },
                    )
            } else if (mapped.umlInterface != null) {
                interfaces +=
                    mapped.umlInterface.copy(
                        attributes = attributes.sortedBy { it.name },
                        operations = operations.sortedBy { it.name },
                    )
            }
        }
    }

    private fun collectFiles(request: ReverseRequest): List<Path> {
        // Normalize globs: strip leading "**/" prefix so they can be matched against
        // the filename component only. For the default "**/*.java" this yields "*.java".
        // Full-path glob matching requires an absolute path matcher which differs per OS;
        // filename matching is simpler and sufficient for the include/exclude semantics here.
        fun normGlob(glob: String): String = if (glob.startsWith("**/")) glob.removePrefix("**/") else glob

        val matchers =
            request.includeGlobs.map { glob ->
                FileSystems.getDefault().getPathMatcher("glob:${normGlob(glob)}")
            }
        val excludeMatchers =
            request.excludeGlobs.map { glob ->
                FileSystems.getDefault().getPathMatcher("glob:${normGlob(glob)}")
            }

        val collected = mutableListOf<Path>()
        for (root in request.sourceRoots) {
            if (!Files.isDirectory(root)) continue
            val allFiles =
                Files.walk(root).use { s ->
                    s.filter { Files.isRegularFile(it) }.toList()
                }
            allFiles
                .filter { p ->
                    val name = p.fileName
                    matchers.any { m -> m.matches(name) } && excludeMatchers.none { m -> m.matches(name) }
                }.forEach { p -> collected.add(p) }
        }
        return collected.sortedBy { it.toString() }
    }
}
