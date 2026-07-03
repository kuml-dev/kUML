package dev.kuml.codegen.reverse.java

import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * End-to-end tests that parse real Java sample files and verify the UML model structure.
 *
 * Sample files are loaded from src/test/resources/java-samples/ into a temp directory
 * to keep tests environment-independent.
 */
class JavaSourceReverseEngineEndToEndTest :
    FunSpec({

        val engine = JavaSourceReverseEngine()

        /**
         * Copies a corpus from resources to tmpDir preserving the package sub-path.
         * Files in `java-samples/bank/` have `package bank;` — so they must live
         * under `tmpDir/bank/` for JavaParserTypeSolver to find them correctly.
         */
        fun copyCorpus(
            name: String,
            tmpDir: Path,
        ) {
            val baseUrl =
                JavaSourceReverseEngineEndToEndTest::class.java
                    .classLoader
                    .getResource("java-samples/$name") ?: error("corpus '$name' not found")
            val basePath = Path.of(baseUrl.toURI())
            val targetDir = tmpDir.resolve(name) // e.g. tmpDir/bank/
            Files.createDirectories(targetDir)
            Files.walk(basePath).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { src ->
                    val dst = targetDir.resolve(src.fileName.toString())
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        test("bank corpus produces three UmlClass and one UmlAssociation per FK field") {
            val tmpDir = Files.createTempDirectory("e2e-bank")
            try {
                copyCorpus("bank", tmpDir)
                val result =
                    runBlocking {
                        engine.analyze(
                            ReverseRequest(
                                sourceRoots = listOf(tmpDir),
                                targetModelName = "BankModel",
                            ),
                        )
                    }
                val success = result.shouldBeInstanceOf<ReverseResult.Success>()
                val diagram = success.model.root.shouldBeInstanceOf<KumlDiagram>()
                val classes = diagram.elements.filterIsInstance<UmlClass>()
                // User, Account, Transaction
                classes.size shouldBe 3
                classes.map { it.name }.toSet() shouldBe setOf("User", "Account", "Transaction")

                // Account.owner → UmlAssociation to User
                // Transaction.account → UmlAssociation to Account
                val associations = diagram.elements.filterIsInstance<UmlAssociation>()
                associations.size shouldBe 2

                success.filesAnalysed shouldBe 3
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }

        test("library corpus roundtrip — counts classes, associations, generalizations against snapshot") {
            val tmpDir = Files.createTempDirectory("e2e-library")
            try {
                copyCorpus("library", tmpDir)
                val result =
                    runBlocking {
                        engine.analyze(
                            ReverseRequest(
                                sourceRoots = listOf(tmpDir),
                                targetModelName = "LibraryModel",
                            ),
                        )
                    }
                val success = result.shouldBeInstanceOf<ReverseResult.Success>()
                val diagram = success.model.root.shouldBeInstanceOf<KumlDiagram>()

                // Author, Book, Library — 3 classes
                val classes = diagram.elements.filterIsInstance<UmlClass>()
                classes.size shouldBe 3

                // Book.author, Author.books (List<Book>), Library.books (List<Book>) = 3 associations
                val associations = diagram.elements.filterIsInstance<UmlAssociation>()
                associations.size shouldBe 3

                // No generalization or realization in the library corpus
                diagram.elements.filterIsInstance<UmlGeneralization>().size shouldBe 0
                diagram.elements.filterIsInstance<UmlInterfaceRealization>().size shouldBe 0

                success.filesAnalysed shouldBe 3
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }

        test("edge corpus emits expected diagnostics REV-J-002, REV-J-003, REV-J-010") {
            val tmpDir = Files.createTempDirectory("e2e-edge")
            try {
                copyCorpus("edge", tmpDir)
                val result =
                    runBlocking {
                        engine.analyze(
                            ReverseRequest(
                                sourceRoots = listOf(tmpDir),
                                targetModelName = "EdgeModel",
                            ),
                        )
                    }
                val success = result.shouldBeInstanceOf<ReverseResult.Success>()
                val codes = success.diagnostics.map { it.code }

                // REV-J-003: Map<String, T> field in GenericContainer
                codes shouldHaveAtLeastSize 1
                codes.any { it == "REV-J-003" } shouldBe true

                // REV-J-002: UnknownExternalType in GenericContainer
                codes.any { it == "REV-J-002" } shouldBe true

                // REV-J-010: GenericContainer<T> is a generic class
                codes.any { it == "REV-J-010" } shouldBe true

                // Outer class should appear in model
                val diagram = success.model.root.shouldBeInstanceOf<KumlDiagram>()
                val classNames = diagram.elements.filterIsInstance<UmlClass>().map { it.name }
                classNames.any { it == "Outer" } shouldBe true

                // GenericContainer emitted with «template» stereotype
                val genericContainer = diagram.elements.filterIsInstance<UmlClass>().firstOrNull { it.name == "GenericContainer" }
                genericContainer?.stereotypes?.any { it == "template" } shouldBe true
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }
    })
