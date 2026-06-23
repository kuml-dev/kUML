package dev.kuml.cli.plugin

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlCli
import dev.kuml.cli.KumlVersion
import dev.kuml.plugin.loader.manifest.PluginManifestParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

class PluginInitCommandTest :
    FunSpec({

        // ── Derivation helpers ──────────────────────────────────────────────

        context("PluginInitSpec.deriveArtifactId") {
            test("extracts last segment") {
                PluginInitSpec.deriveArtifactId("com.example.my-theme") shouldBe "my-theme"
            }

            test("lowercases result") {
                PluginInitSpec.deriveArtifactId("com.example.MyRenderer") shouldBe "myrenderer"
            }

            test("single-segment id passes through") {
                PluginInitSpec.deriveArtifactId("mytheme") shouldBe "mytheme"
            }
        }

        context("PluginInitSpec.derivePackageName") {
            test("strips dashes from segments") {
                PluginInitSpec.derivePackageName("com.example.my-theme") shouldBe "com.example.mytheme"
            }

            test("single-segment id gets dev.kuml.plugin prefix") {
                PluginInitSpec.derivePackageName("mytheme") shouldContain "dev.kuml.plugin."
            }
        }

        context("PluginInitSpec.deriveGroupId") {
            test("strips last segment from multi-segment id") {
                PluginInitSpec.deriveGroupId("com.example.my-theme") shouldBe "com.example"
            }

            test("multi-level group strips last segment") {
                PluginInitSpec.deriveGroupId("org.acme.subgroup.myplugin") shouldBe "org.acme.subgroup"
            }

            test("single-segment id returns dev.kuml.plugin") {
                PluginInitSpec.deriveGroupId("mytheme") shouldBe "dev.kuml.plugin"
            }
        }

        context("PluginInitSpec.deriveClassName") {
            test("PascalCase + category + Plugin suffix") {
                PluginInitSpec.deriveClassName("my-theme", "theme") shouldBe "MyThemePlugin"
            }

            test("codegen suffix") {
                PluginInitSpec.deriveClassName("typescript", "codegen") shouldBe "TypescriptCodegenPlugin"
            }

            test("reverse suffix") {
                PluginInitSpec.deriveClassName("my-reverse", "reverse") shouldBe "MyReversePlugin"
            }
        }

        context("TemplateEngine") {
            test("replaces known tokens") {
                val result = TemplateEngine.render("Hello {{name}}!", mapOf("name" to "kUML"))
                result shouldBe "Hello kUML!"
            }

            test("throws on unknown token") {
                val ex = runCatching { TemplateEngine.render("{{unknown}}", emptyMap()) }
                ex.isFailure shouldBe true
            }

            test("leaves no unresolved {{}} in rendered output when all vars provided") {
                val vars =
                    mapOf(
                        "pluginId" to "com.example.t",
                        "pluginName" to "T",
                        "artifactId" to "t",
                        "packageName" to "com.example.t",
                        "packageDir" to "com/example/t",
                        "className" to "TThemePlugin",
                        "groupId" to "com.example",
                        "version" to "1.0.0",
                        "maintainer" to "Alice",
                        "homepage" to "https://example.com",
                        "licenseSpdx" to "Apache-2.0",
                        "kumlVersion" to KumlVersion.version,
                        "kumlVersionRange" to ">=0.12.0",
                        "category" to "theme",
                    )
                val template = "{{pluginId}} {{artifactId}} {{className}}"
                TemplateEngine.render(template, vars) shouldNotContain "{{"
            }
        }

        // ── Per-category scaffold ───────────────────────────────────────────

        fun buildSpec(
            category: String,
            pluginId: String = "com.example.my-$category",
        ): PluginInitSpec =
            PluginInitSpec.from(
                category = category,
                pluginId = pluginId,
                pluginName = "My ${category.replaceFirstChar { it.uppercaseChar() }}",
                maintainer = "Test Author",
                homepage = "https://example.com",
                licenseSpdx = "Apache-2.0",
            )

        fun expectedFiles(spec: PluginInitSpec): List<String> =
            listOf(
                "build.gradle.kts",
                "settings.gradle.kts",
                ".gitignore",
                "src/main/resources/kuml-plugin.json",
                "README.adoc",
                "src/main/kotlin/${spec.packageDir}/${spec.className}.kt",
            )

        listOf("theme", "renderer", "layout", "codegen", "reverse").forEach { category ->
            context("scaffold: $category") {
                test("creates expected file set") {
                    val tmpDir = Files.createTempDirectory("kuml-init-test-$category").toFile()
                    val spec = buildSpec(category)
                    PluginScaffolder.scaffold(category, spec, tmpDir, force = true, echo = {})

                    val created =
                        tmpDir
                            .walkTopDown()
                            .filter { it.isFile }
                            .map { it.relativeTo(tmpDir).path }
                            .toSet()

                    for (expected in expectedFiles(spec)) {
                        created.any { it.replace(File.separatorChar, '/') == expected } shouldBe true
                    }

                    tmpDir.deleteRecursively()
                }

                test("generated manifest is parseable and correct") {
                    val tmpDir = Files.createTempDirectory("kuml-manifest-test-$category").toFile()
                    val spec = buildSpec(category)
                    PluginScaffolder.scaffold(category, spec, tmpDir, force = true, echo = {})

                    val manifestFile = File(tmpDir, "src/main/resources/kuml-plugin.json")
                    val manifest = PluginManifestParser.parse(manifestFile.readText())

                    manifest.id shouldBe spec.pluginId
                    manifest.extensions.size shouldBe 1
                    manifest.extensions.single().category shouldBe category

                    tmpDir.deleteRecursively()
                }

                test("manifest permissions match category contract") {
                    val tmpDir = Files.createTempDirectory("kuml-perms-test-$category").toFile()
                    val spec = buildSpec(category)
                    PluginScaffolder.scaffold(category, spec, tmpDir, force = true, echo = {})

                    val manifestFile = File(tmpDir, "src/main/resources/kuml-plugin.json")
                    val manifest = PluginManifestParser.parse(manifestFile.readText())

                    when (category) {
                        "codegen" -> manifest.permissions shouldContainExactlyInAnyOrder listOf("fs.write")
                        "reverse" -> manifest.permissions shouldContainExactlyInAnyOrder listOf("fs.read")
                        else -> manifest.permissions shouldBe emptyList()
                    }

                    tmpDir.deleteRecursively()
                }

                test("build.gradle.kts has correct kUML coordinates") {
                    val tmpDir = Files.createTempDirectory("kuml-gradle-test-$category").toFile()
                    val spec = buildSpec(category)
                    PluginScaffolder.scaffold(category, spec, tmpDir, force = true, echo = {})

                    val gradle = File(tmpDir, "build.gradle.kts").readText()
                    gradle shouldContain "dev.kuml:kuml-plugin-api-$category:"
                    gradle shouldContain "dev.kuml:kuml-plugin-api-core:"
                    gradle shouldContain spec.kumlVersion

                    tmpDir.deleteRecursively()
                }

                test("no unresolved template tokens in any generated file") {
                    val tmpDir = Files.createTempDirectory("kuml-tokens-test-$category").toFile()
                    val spec = buildSpec(category)
                    PluginScaffolder.scaffold(category, spec, tmpDir, force = true, echo = {})

                    tmpDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val content = file.readText()
                        content shouldNotContain "{{" // no unresolved tokens
                    }

                    tmpDir.deleteRecursively()
                }

                test("force flag allows overwriting existing dir") {
                    val tmpDir = Files.createTempDirectory("kuml-force-test-$category").toFile()
                    val spec = buildSpec(category)
                    PluginScaffolder.scaffold(category, spec, tmpDir, force = true, echo = {})
                    PluginScaffolder.scaffold(category, spec, tmpDir, force = true, echo = {})

                    tmpDir.deleteRecursively()
                }

                test("non-force on non-empty dir throws") {
                    val tmpDir = Files.createTempDirectory("kuml-noforce-test-$category").toFile()
                    File(tmpDir, "existing.txt").writeText("content")
                    val spec = buildSpec(category)

                    val result =
                        runCatching {
                            PluginScaffolder.scaffold(category, spec, tmpDir, force = false, echo = {})
                        }
                    result.isFailure shouldBe true
                    result.exceptionOrNull() shouldNotBe null

                    tmpDir.deleteRecursively()
                }
            }
        }

        // ── Cross-category derivation test ──────────────────────────────────

        context("PluginInitSpec.from derivation for com.example.my-theme") {
            val spec = buildSpec("theme", "com.example.my-theme")

            test("artifactId") { spec.artifactId shouldBe "my-theme" }
            test("packageName") { spec.packageName shouldBe "com.example.mytheme" }
            test("groupId") { spec.groupId shouldBe "com.example" }
            test("className") { spec.className shouldBe "MyThemePlugin" }
            test("packageDir") { spec.packageDir shouldBe "com/example/mytheme" }
            test("kumlVersion") { spec.kumlVersion shouldBe KumlVersion.version }
            test("kumlVersionRange") { spec.kumlVersionRange shouldBe ">=0.12.0" }
        }

        // ── CLI integration tests ────────────────────────────────────────────

        context("plugin init CLI integration") {
            test("non-interactive with all required flags exits 0 and creates output directory") {
                val tmpDir = Files.createTempDirectory("kuml-cli-init-test").toFile()
                val outputDir = File(tmpDir, "t")
                try {
                    val result =
                        KumlCli().test(
                            "plugin init theme --non-interactive --id com.example.t --name T --maintainer Alice --output ${outputDir.absolutePath}",
                        )
                    result.statusCode shouldBe 0
                    outputDir.exists() shouldBe true
                    outputDir.isDirectory shouldBe true
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("non-interactive without --id exits with USAGE (2)") {
                val tmpDir = Files.createTempDirectory("kuml-cli-init-noid").toFile()
                try {
                    val result =
                        KumlCli().test(
                            "plugin init theme --non-interactive --name T --maintainer Alice --output ${tmpDir.absolutePath}",
                        )
                    result.statusCode shouldBe ExitCodes.USAGE
                } finally {
                    tmpDir.deleteRecursively()
                }
            }

            test("non-interactive without --category exits with USAGE (2)") {
                val tmpDir = Files.createTempDirectory("kuml-cli-init-nocat").toFile()
                try {
                    val result =
                        KumlCli().test(
                            "plugin init --non-interactive --id com.example.t --name T --maintainer Alice --output ${tmpDir.absolutePath}",
                        )
                    result.statusCode shouldBe ExitCodes.USAGE
                } finally {
                    tmpDir.deleteRecursively()
                }
            }
        }
    })
