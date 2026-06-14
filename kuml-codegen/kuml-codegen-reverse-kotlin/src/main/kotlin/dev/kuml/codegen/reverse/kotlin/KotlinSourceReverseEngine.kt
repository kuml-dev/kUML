package dev.kuml.codegen.reverse.kotlin

import dev.kuml.codegen.reverse.KumlReverseEngine
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.codegen.reverse.kotlin.mapper.KtAssociationDetector
import dev.kuml.codegen.reverse.kotlin.mapper.KtClassMapper
import dev.kuml.codegen.reverse.kotlin.mapper.KtEnumerationMapper
import dev.kuml.codegen.reverse.kotlin.mapper.KtFunctionMapper
import dev.kuml.codegen.reverse.kotlin.mapper.KtGeneralizationMapper
import dev.kuml.codegen.reverse.kotlin.mapper.KtInterfaceMapper
import dev.kuml.codegen.reverse.kotlin.mapper.KtObjectMapper
import dev.kuml.codegen.reverse.kotlin.mapper.KtParameterMapper
import dev.kuml.codegen.reverse.kotlin.mapper.KtPropertyMapper
import dev.kuml.codegen.reverse.kotlin.mapper.KtTypeResolver
import dev.kuml.codegen.reverse.kotlin.support.DiagnosticCollector
import dev.kuml.codegen.reverse.kotlin.support.KtFqnPool
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtObjectDeclaration

/**
 * Kotlin PSI-based implementation of [KumlReverseEngine] for `*.kt` sources.
 *
 * Pipeline:
 * 1. Build [KotlinAnalysisSession] — initializes KotlinCoreEnvironment and loads KtFiles.
 * 2. Pass 1: [KtFqnPool.build] — collects all classifiers into a FQN map.
 * 3. Pass 2: Walk all declarations — map classifiers via typed mappers.
 * 4. Post-pass: generalization/realization via [KtGeneralizationMapper].
 * 5. Post-pass: association detection via [KtAssociationDetector].
 * 6. Assemble [KumlModel] and return [ReverseResult.Success].
 *
 * JVM-only: never include in GraalVM Native Image.
 */
public class KotlinSourceReverseEngine : KumlReverseEngine {
    override val id: String = "kotlin"
    override val description: String = "Kotlin source → UML via kotlin-compiler-embeddable PSI"

    override suspend fun analyze(request: ReverseRequest): ReverseResult {
        val startMs = System.currentTimeMillis()
        val diagnostics = DiagnosticCollector()

        val session = KotlinAnalysisSession(request, diagnostics)
        try {
            val ktFiles = session.loadKtFiles()

            if (ktFiles.isEmpty()) {
                return ReverseResult.Failure(
                    buildList {
                        add(
                            dev.kuml.codegen.reverse.ReverseDiagnostic(
                                severity = dev.kuml.codegen.reverse.ReverseDiagnostic.Severity.ERROR,
                                code = "REV-CORE-001",
                                message = "No Kotlin source files found in source roots: ${request.sourceRoots}",
                            ),
                        )
                    },
                )
            }

            // Pass 1: build FQN pool
            val pool = KtFqnPool.build(ktFiles, diagnostics)

            // Instantiate mappers
            val typeResolver = KtTypeResolver(pool)
            val propertyMapper = KtPropertyMapper(typeResolver)
            val paramMapper = KtParameterMapper(typeResolver)
            val functionMapper = KtFunctionMapper(paramMapper, typeResolver)
            val classMapper = KtClassMapper(propertyMapper, functionMapper, diagnostics)
            val interfaceMapper = KtInterfaceMapper(propertyMapper, functionMapper)
            val objectMapper = KtObjectMapper(propertyMapper, functionMapper, diagnostics)
            val enumMapper = KtEnumerationMapper(diagnostics)

            // Pass 2: walk all known FQNs and map classifiers
            val classes = mutableListOf<UmlClass>()
            val interfaces = mutableListOf<UmlInterface>()
            val enumerations = mutableListOf<UmlEnumeration>()

            for (fqn in pool.allFqns().sorted()) {
                val decl = pool.resolve(fqn) ?: continue
                val id = pool.idOf(fqn) ?: continue

                when {
                    decl is KtClass && decl.isEnum() -> {
                        enumerations += enumMapper.map(decl, fqn, id)
                    }
                    decl is KtClass && decl.isInterface() -> {
                        interfaces += interfaceMapper.map(decl, fqn, id)
                    }
                    decl is KtClass -> {
                        classes += classMapper.map(decl, fqn, id)
                    }
                    decl is KtObjectDeclaration -> {
                        objectMapper.map(decl, fqn, id)?.let { classes += it }
                    }
                }
            }

            // Pass 2b: emit diagnostics for top-level non-classifier declarations
            for (file in ktFiles) {
                val filePath = file.virtualFilePath
                for (decl in file.declarations) {
                    when (decl) {
                        is org.jetbrains.kotlin.psi.KtClassOrObject -> { /* handled by FQN pool */ }
                        is org.jetbrains.kotlin.psi.KtNamedFunction -> {
                            diagnostics.info(
                                code = "REV-K-011",
                                message = "Top-level function '${decl.name}' skipped — no UML container for free functions.",
                                file = filePath,
                            )
                        }
                        is org.jetbrains.kotlin.psi.KtProperty -> {
                            diagnostics.info(
                                code = "REV-K-012",
                                message = "Top-level property '${decl.name}' skipped — no UML container for top-level properties.",
                                file = filePath,
                            )
                        }
                        is org.jetbrains.kotlin.psi.KtTypeAlias -> {
                            diagnostics.info(
                                code = "REV-K-013",
                                message = "typealias '${decl.name}' skipped — no UML equivalent.",
                                file = filePath,
                            )
                        }
                        else -> {
                            diagnostics.info(
                                code = "REV-K-010",
                                message = "Top-level declaration of type '${decl::class.simpleName}' skipped.",
                                file = filePath,
                            )
                        }
                    }
                }
            }

            // Post-pass: generalization/realization relationships
            val genMapper = KtGeneralizationMapper(pool, diagnostics)
            val genResult = genMapper.mapAll()

            // Post-pass: association detection
            val assocDetector = KtAssociationDetector(pool)
            val associations = assocDetector.detect(classes, interfaces)

            // Assemble element list in deterministic order
            val elements: List<UmlElement> =
                buildList {
                    addAll(enumerations.sortedBy { it.name })
                    addAll(interfaces.sortedBy { it.name })
                    addAll(classes.sortedBy { it.name })
                    addAll(
                        genResult.generalizations.sortedWith(
                            compareBy({ it.specificId }, { it.generalId }),
                        ),
                    )
                    addAll(
                        genResult.realizations.sortedWith(
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

            return ReverseResult.Success(
                model = model,
                diagnostics = diagnostics.all(),
                filesAnalysed = ktFiles.size,
                elapsedMs = System.currentTimeMillis() - startMs,
            )
        } finally {
            session.dispose()
        }
    }
}
