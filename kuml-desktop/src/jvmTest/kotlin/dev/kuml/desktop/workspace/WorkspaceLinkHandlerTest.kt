package dev.kuml.desktop.workspace

import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfDocumentParser
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.net.URI
import java.nio.file.Files

class WorkspaceLinkHandlerTest :
    FunSpec({

        fun writeSampleWorkspace(): java.io.File {
            val root = Files.createTempDirectory("kuml-link-handler-test").toFile()
            java.io.File(root, "index.md").writeText(
                """
                ---
                type: KumlWorkspace
                ---
                # Index
                """.trimIndent(),
            )
            java.io.File(root, "articles").mkdirs()
            java.io.File(root, "articles/01-intro.md").writeText(
                """
                ---
                type: Article
                ---
                [back to index](../index.md)
                """.trimIndent(),
            )
            return root
        }

        test("external https link is opened via the injected opener") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val indexDoc = workspace.documents.first { it.relativePath == "index.md" }
                val opened = mutableListOf<URI>()
                val navigated = mutableListOf<OkfDocument>()
                val handler =
                    DefaultWorkspaceLinkHandler(
                        documents = { workspace.documents },
                        currentDoc = { indexDoc },
                        onNavigate = { navigated.add(it) },
                        openExternal = { uri -> opened.add(uri) },
                    )

                handler.onLink("https://kuml.dev")

                opened shouldBe listOf(URI("https://kuml.dev"))
                navigated.shouldBeEmpty()
            } finally {
                root.deleteRecursively()
            }
        }

        test("file: and javascript: schemes are refused") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val indexDoc = workspace.documents.first { it.relativePath == "index.md" }
                val opened = mutableListOf<URI>()
                val handler =
                    DefaultWorkspaceLinkHandler(
                        documents = { workspace.documents },
                        currentDoc = { indexDoc },
                        onNavigate = {},
                        openExternal = { uri -> opened.add(uri) },
                    )

                handler.onLink("file:///etc/passwd")
                handler.onLink("javascript:alert(1)")

                opened.shouldBeEmpty()
            } finally {
                root.deleteRecursively()
            }
        }

        test("internal relative link resolving to a scanned document navigates to it") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val articleDoc = workspace.documents.first { it.relativePath == "articles/01-intro.md" }
                val indexDoc = workspace.documents.first { it.relativePath == "index.md" }
                val navigated = mutableListOf<OkfDocument>()
                val handler =
                    DefaultWorkspaceLinkHandler(
                        documents = { workspace.documents },
                        currentDoc = { articleDoc },
                        onNavigate = { navigated.add(it) },
                    )

                handler.onLink("../index.md")

                navigated shouldBe listOf(indexDoc)
            } finally {
                root.deleteRecursively()
            }
        }

        test("a path-traversal escape target is unresolved — no navigation, no file read") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val articleDoc = workspace.documents.first { it.relativePath == "articles/01-intro.md" }
                val navigated = mutableListOf<OkfDocument>()
                val handler =
                    DefaultWorkspaceLinkHandler(
                        documents = { workspace.documents },
                        currentDoc = { articleDoc },
                        onNavigate = { navigated.add(it) },
                    )

                handler.onLink("../../../../../../../../etc/passwd")

                navigated.shouldBeEmpty()
            } finally {
                root.deleteRecursively()
            }
        }

        test("after a document is replaced in place, the handler navigates to the NEW object, not a stale snapshot") {
            val root = writeSampleWorkspace()
            try {
                val workspace = WorkspaceScanner.scan(root = root)
                val staleIndexDoc = workspace.documents.first { it.relativePath == "index.md" }
                val articleDoc = workspace.documents.first { it.relativePath == "articles/01-intro.md" }

                // Simulate WorkspaceState.applySaved: index.md's content changed on disk, and the
                // caller's document list was updated with a freshly re-parsed object — but the
                // handler was built with a `documents` SUPPLIER, not a frozen list captured at
                // workspace-open time.
                var currentDocuments = workspace.documents
                val navigated = mutableListOf<OkfDocument>()
                val handler =
                    DefaultWorkspaceLinkHandler(
                        documents = { currentDocuments },
                        currentDoc = { articleDoc },
                        onNavigate = { navigated.add(it) },
                    )

                File(root, "index.md").writeText(
                    """
                    ---
                    type: KumlWorkspace
                    title: Updated after save
                    ---
                    # Index (edited)
                    """.trimIndent(),
                )
                val reparsedIndex = OkfDocumentParser.parseFile(root = root, file = staleIndexDoc.file)
                currentDocuments = currentDocuments.map { if (it.relativePath == "index.md") reparsedIndex else it }

                handler.onLink("../index.md")

                navigated shouldBe listOf(reparsedIndex)
                navigated.single() shouldNotBe staleIndexDoc
            } finally {
                root.deleteRecursively()
            }
        }
    })
