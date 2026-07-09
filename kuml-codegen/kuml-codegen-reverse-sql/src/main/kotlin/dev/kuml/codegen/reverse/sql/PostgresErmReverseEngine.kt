package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.erm.ErmReverseEngine
import dev.kuml.codegen.reverse.erm.ErmReverseResult
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.ErmView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.create.index.CreateIndex
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * JSqlParser-based implementation of [ErmReverseEngine] for Postgres `.sql` DDL
 * sources (V3.4.9).
 *
 * Pipeline:
 * 1. Glob-collect `.sql` files matching [ReverseRequest.includeGlobs] (default
 *    `**&#47;*.sql`) under [ReverseRequest.sourceRoots] — a source root that is
 *    itself a regular file is always included, regardless of the glob.
 * 2. Parse every file into top-level statements via [SqlStatementCollector].
 * 3. Pass 1 — map every `CREATE TABLE` to an entity ([TableMapper]); build the
 *    folded-table-name → entity-id index.
 * 4. Pass 2 — apply `ALTER TABLE` (`ADD CONSTRAINT`/`ADD PRIMARY KEY`/`ADD
 *    UNIQUE`/`ADD COLUMN`), `CREATE INDEX`, and `CREATE VIEW` statements
 *    ([ConstraintResolver]); resolve every pending foreign key against the
 *    name index.
 * 5. Derive [dev.kuml.erm.model.ErmRelationship]s from the resolved foreign
 *    keys ([RelationshipInferrer]).
 *
 * JVM-only: never include in GraalVM Native Image — JSqlParser is JavaCC-generated,
 * reflection-heavy parser infrastructure, exactly like `kuml-codegen-reverse-java`'s
 * JavaParser dependency (see `kuml-cli/build.gradle.kts`: `runtimeOnly`, never
 * `implementation`).
 */
public class PostgresErmReverseEngine : ErmReverseEngine {
    override val id: String = "sql-postgres"
    override val dialect: String = "postgres"
    override val description: String = "JSqlParser-based Postgres SQL DDL → ERM reverse engine"

    override suspend fun analyze(request: ReverseRequest): ErmReverseResult =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val diagnostics = mutableListOf<ReverseDiagnostic>()

            val files = collectFiles(request)
            if (files.isEmpty()) {
                return@withContext ErmReverseResult.Failure(
                    listOf(
                        ReverseDiagnostic(
                            severity = ReverseDiagnostic.Severity.ERROR,
                            code = "REV-SQL-001",
                            message = "No .sql source files found in source roots: ${request.sourceRoots}",
                        ),
                    ),
                )
            }

            val parsed = SqlStatementCollector.collect(files, diagnostics)
            if (parsed.isEmpty()) {
                return@withContext ErmReverseResult.Failure(
                    listOf(
                        ReverseDiagnostic(
                            severity = ReverseDiagnostic.Severity.ERROR,
                            code = "REV-SQL-001",
                            message = "No parsable SQL statements found in: ${request.sourceRoots}",
                        ),
                    ),
                )
            }

            // Pass 1: CREATE TABLE → entities + pending foreign keys.
            val entities = LinkedHashMap<String, MutableErmEntity>()
            val nameIndex = LinkedHashMap<String, String>()
            val pendingForeignKeys = mutableListOf<PendingForeignKey>()
            var entityIx = 0
            for ((stmt, fileName) in parsed) {
                if (stmt !is CreateTable) continue
                val entityId = "entity_$entityIx"
                val entity = TableMapper.map(stmt, entityId, entityIx, diagnostics, pendingForeignKeys, fileName)
                entities[entityId] = entity
                nameIndex[SqlIdentifiers.fold(stmt.table.name)] = entityId
                entityIx++
            }

            // Pass 2: ALTER TABLE (needs the full name index — forward references + ADD CONSTRAINT FK).
            for ((stmt, fileName) in parsed) {
                if (stmt !is Alter) continue
                ConstraintResolver.applyAlter(stmt, entities, nameIndex, pendingForeignKeys, diagnostics, fileName)
            }

            ConstraintResolver.resolveForeignKeys(pendingForeignKeys, entities, nameIndex, diagnostics, null)

            for ((stmt, fileName) in parsed) {
                if (stmt !is CreateIndex) continue
                ConstraintResolver.applyCreateIndex(stmt, entities, nameIndex, diagnostics, fileName)
            }

            val views = mutableListOf<ErmView>()
            var viewIx = 0
            for ((stmt, fileName) in parsed) {
                if (stmt !is CreateView) continue
                views += ConstraintResolver.mapView(stmt, viewIx, nameIndex, diagnostics, fileName)
                viewIx++
            }

            val relationships = RelationshipInferrer.infer(entities)

            val model =
                ErmModel(
                    name = request.targetModelName,
                    entities = entities.values.map { it.toErmEntity() },
                    relationships = relationships,
                    views = views,
                    diagrams = listOf(ErmDiagram(name = request.targetModelName, notation = ErmNotation.MARTIN, showIndexes = true)),
                )

            ErmReverseResult.Success(
                model = model,
                diagnostics = diagnostics,
                filesAnalysed = files.size,
                elapsedMs = System.currentTimeMillis() - startMs,
            )
        }

    private fun collectFiles(request: ReverseRequest): List<Path> {
        fun normGlob(glob: String): String = if (glob.startsWith("**/")) glob.removePrefix("**/") else glob

        val matchers = request.includeGlobs.map { glob -> FileSystems.getDefault().getPathMatcher("glob:${normGlob(glob)}") }
        val excludeMatchers = request.excludeGlobs.map { glob -> FileSystems.getDefault().getPathMatcher("glob:${normGlob(glob)}") }

        val collected = mutableListOf<Path>()
        for (root in request.sourceRoots) {
            when {
                Files.isRegularFile(root) -> {
                    // An explicit single-file source root (`kuml reverse schema.sql --format sql`)
                    // is always included, regardless of --include/--exclude.
                    collected.add(root)
                }
                Files.isDirectory(root) -> {
                    val allFiles = Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
                    allFiles
                        .filter { p ->
                            val name = p.fileName
                            matchers.any { it.matches(name) } && excludeMatchers.none { it.matches(name) }
                        }.forEach { collected.add(it) }
                }
            }
        }
        return collected.sortedBy { it.toString() }
    }
}
