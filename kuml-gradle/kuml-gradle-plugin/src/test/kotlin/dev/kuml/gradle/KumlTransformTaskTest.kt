package dev.kuml.gradle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files

private const val HEADER =
    """import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype
"""

private fun idAttr() =
    """        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }"""

private fun entity(
    variable: String,
    name: String,
    table: String,
    obj: String,
    extra: List<String> = emptyList(),
): String =
    """    val $variable = classOf(name = "$name") {
        stereotype("Entity") { "tableName" to "$table"; "kotlinObjectName" to "$obj" }
${idAttr()}
${extra.joinToString(
        "\n",
    ) { "        attribute(name = \"$it\", type = \"String\") { stereotype(\"Column\") { \"columnName\" to \"$it\" } }" }}
    }"""

private fun diagram(
    name: String,
    body: List<String>,
): String = "$HEADER\nclassDiagram(name = \"$name\") {\n    applyProfile(ermMappingProfile)\n\n${body.joinToString("\n\n")}\n}\n"

private val assocOrderMember =
    """    association(source = order, target = member, id = "assoc-order-member") {
        source { multiplicity("0..*") }
        target { multiplicity("1"); role = "memberId" }
    }"""
private val assocOrderTag =
    """    association(source = order, target = tag, id = "assoc-order-tag") {
        source { multiplicity("0..*") }
        target { multiplicity("0..*") }
    }"""

private val memberFull = entity(variable = "member", name = "Member", table = "member", obj = "MemberTable", extra = listOf("name"))
private val memberStub = entity(variable = "member", name = "Member", table = "member", obj = "MemberTable")
private val orderFull =
    entity(variable = "order", name = "Order", table = "customer_order", obj = "CustomerOrderTable", extra = listOf("total"))
private val orderStub = entity(variable = "order", name = "Order", table = "customer_order", obj = "CustomerOrderTable")
private val tagFull = entity(variable = "tag", name = "Tag", table = "tag", obj = "TagTable", extra = listOf("label"))

private val splitScripts =
    mapOf(
        "00-members.kuml.kts" to diagram(name = "Members", body = listOf(memberFull)),
        "10-orders.kuml.kts" to diagram(name = "Orders", body = listOf(memberStub, orderFull, assocOrderMember)),
        "20-tags.kuml.kts" to diagram(name = "Tags", body = listOf(orderStub, tagFull, assocOrderTag)),
    )

private val singleScripts =
    mapOf("model.kuml.kts" to diagram(name = "Shop", body = listOf(memberFull, orderFull, tagFull, assocOrderMember, assocOrderTag)))

private fun newProject(
    scripts: Map<String, String>,
    kumlBlock: String =
        """transformer.set("uml-to-exposed-via-erm")
           transformOptions.putAll(mapOf("idType" to "uuid", "package" to "demo.db"))""",
    settingsExtra: String = "",
): File {
    val dir = Files.createTempDirectory("kuml-transform-").toFile()
    dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"kuml-transform-test\"\n$settingsExtra")
    dir.resolve("build.gradle.kts").writeText(
        """
        plugins { id("dev.kuml") }
        kuml {
            sourceDir.set(file("kuml"))
            outputDir.set(layout.buildDirectory.dir("kuml"))
            $kumlBlock
        }
        """.trimIndent(),
    )
    val src = dir.resolve("kuml").apply { mkdirs() }
    scripts.forEach { (name, body) -> src.resolve(name).apply { parentFile.mkdirs() }.writeText(body) }
    return dir
}

private fun runner(
    project: File,
    args: List<String>,
) = GradleRunner
    .create()
    .withProjectDir(project)
    .withPluginClasspath()
    .withArguments(args + "--stacktrace")

private fun File.contents(): Map<String, String> =
    walkTopDown().filter { it.isFile }.associate { it.relativeTo(this).invariantSeparatorsPath to it.readText() }

class KumlTransformTaskTest :
    FunSpec({

        fun withProject(
            project: File,
            block: (File) -> Unit,
        ) = try {
            block(project)
        } finally {
            project.deleteRecursively()
        }

        test("split and single projects generate identical files, second run is UP-TO-DATE, stale output is removed") {
            val split = newProject(scripts = splitScripts)
            val single = newProject(scripts = singleScripts)
            try {
                val r1 = runner(project = split, args = listOf("kumlTransform")).build()
                r1.task(":kumlTransform")?.outcome shouldBe TaskOutcome.SUCCESS
                r1.output shouldContain "merged 3 script(s) -> 4 file(s)"
                runner(project = single, args = listOf("kumlTransform")).build()

                val splitFiles = split.resolve("build/kuml/transform").contents()
                splitFiles.keys.sorted() shouldBe listOf("CustomerOrderTable.kt", "MemberTable.kt", "OrdersTags.kt", "TagTable.kt")
                splitFiles shouldBe single.resolve("build/kuml/transform").contents()

                runner(project = split, args = listOf("kumlTransform")).build().task(":kumlTransform")?.outcome shouldBe
                    TaskOutcome.UP_TO_DATE

                // Removing the tag script removes its stale outputs (TagTable, junction table).
                split.resolve("kuml/20-tags.kuml.kts").delete()
                val r3 = runner(project = split, args = listOf("kumlTransform")).build()
                r3.task(":kumlTransform")?.outcome shouldBe TaskOutcome.SUCCESS
                split
                    .resolve("build/kuml/transform")
                    .contents()
                    .keys
                    .sorted() shouldBe
                    listOf("CustomerOrderTable.kt", "MemberTable.kt")
            } finally {
                split.deleteRecursively()
                single.deleteRecursively()
            }
        }

        test("build cache restores the output, and the configuration cache is reused") {
            val cacheDir = Files.createTempDirectory("kuml-build-cache-").toFile()
            val project =
                newProject(
                    scripts = splitScripts,
                    settingsExtra =
                        "buildCache { local { directory = file(\"${cacheDir.invariantSeparatorsPath}\") } }\n",
                )
            try {
                runner(project = project, args = listOf("kumlTransform", "--build-cache")).build().task(":kumlTransform")?.outcome shouldBe
                    TaskOutcome.SUCCESS
                project.resolve("build").deleteRecursively()
                val cached = runner(project = project, args = listOf("kumlTransform", "--build-cache")).build()
                cached.task(":kumlTransform")?.outcome shouldBe TaskOutcome.FROM_CACHE
                project.resolve("build/kuml/transform").contents().size shouldBe 4

                project.resolve("build").deleteRecursively()
                runner(project = project, args = listOf("kumlTransform", "--configuration-cache")).build()
                val second = runner(project = project, args = listOf("kumlTransform", "--configuration-cache")).build()
                second.output shouldContain "Reusing configuration cache"
                second.task(":kumlTransform")?.outcome shouldBe TaskOutcome.UP_TO_DATE
            } finally {
                project.deleteRecursively()
                cacheDir.deleteRecursively()
            }
        }

        test("a merge conflict fails the build naming table and attribute") {
            val project =
                newProject(
                    scripts =
                        mapOf(
                            "a.kuml.kts" to diagram(name = "A", body = listOf(memberFull)),
                            "b.kuml.kts" to
                                diagram(
                                    name = "B",
                                    body =
                                        listOf(
                                            entity(
                                                variable = "member",
                                                name = "Member",
                                                table = "member",
                                                obj = "MemberTable",
                                                extra = listOf("nickname"),
                                            ),
                                        ),
                                ),
                        ),
                )
            withProject(project) {
                val result: BuildResult = runner(project = it, args = listOf("kumlTransform")).buildAndFail()
                result.output shouldContain "member"
                result.output shouldContain "nickname"
                result.output shouldContain "a.kuml.kts"
            }
        }

        test("a C4 script fails the build naming the file; skipNonUmlScripts lets it pass") {
            val c4 =
                """
                c4Model(name = "M") {
                    val customer = person(name = "Customer") { description = "End user" }
                    val system = softwareSystem(name = "Main") { description = "Core" }
                    relationship(source = customer, target = system) { technology = "HTTPS" }
                    systemContextDiagram(name = "Context") { include(customer, system) }
                }
                """.trimIndent()
            val scripts = splitScripts + ("sub/system.kuml.kts" to c4)
            val failing = newProject(scripts = scripts)
            withProject(failing) {
                runner(project = it, args = listOf("kumlTransform")).buildAndFail().output shouldContain "sub/system.kuml.kts"
            }
            val skipping =
                newProject(
                    scripts = scripts,
                    kumlBlock =
                        """transformer.set("uml-to-exposed-via-erm")
                           skipNonUmlScripts.set(true)""",
                )
            withProject(skipping) {
                runner(project = it, args = listOf("kumlTransform")).build().task(":kumlTransform")?.outcome shouldBe TaskOutcome.SUCCESS
            }
        }

        test("a missing transformer gives guidance") {
            withProject(newProject(scripts = splitScripts, kumlBlock = "")) {
                runner(project = it, args = listOf("kumlTransform")).buildAndFail().output shouldContain "kuml { transformer.set"
            }
        }

        test("the script count limit fails the build") {
            val project = newProject(scripts = splitScripts)
            project
                .resolve(
                    "build.gradle.kts",
                ).appendText("\ntasks.named<dev.kuml.gradle.KumlTransformTask>(\"kumlTransform\") { maxScripts.set(1) }\n")
            withProject(project) {
                runner(project = it, args = listOf("kumlTransform")).buildAndFail().output shouldContain "limit is 1"
            }
        }

        test("a symlink pointing outside the source directory fails the build") {
            val outside = Files.createTempFile("outside-", ".kuml.kts").toFile()
            outside.writeText(diagram(name = "X", body = listOf(memberFull)))
            val project = newProject(scripts = splitScripts)
            val link = project.resolve("kuml/linked.kuml.kts").toPath()
            val created = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
            try {
                if (created) {
                    runner(project = project, args = listOf("kumlTransform")).buildAndFail().output shouldContain "linked.kuml.kts"
                }
            } finally {
                project.deleteRecursively()
                outside.delete()
            }
        }

        test("outputDir equal to sourceDir fails the build and keeps the scripts") {
            val project = newProject(scripts = splitScripts)
            project.resolve("build.gradle.kts").appendText("\nkuml { outputDir.set(file(\"kuml/transform\")) }\n")
            withProject(project) {
                runner(project = it, args = listOf("kumlTransform")).buildAndFail().output shouldContain "outputDir must not"
                it.resolve("kuml/00-members.kuml.kts").exists() shouldBe true
            }
        }

        test("kumlGenerate with mergeScripts generates one CREATE TABLE per table") {
            val project =
                newProject(
                    scripts = splitScripts,
                    kumlBlock =
                        """generator.set("sql")
                           mergeScripts.set(true)""",
                )
            withProject(project) {
                val result = runner(project = it, args = listOf("kumlGenerate")).build()
                result.output shouldContain "merged 3 script(s)"
                val sql =
                    it
                        .resolve("build/kuml/generated")
                        .walkTopDown()
                        .filter { f ->
                            f.isFile && f.extension == "sql"
                        }.joinToString("\n") { f -> f.readText() }
                listOf("member", "customer_order", "tag").forEach { table ->
                    Regex("CREATE TABLE (IF NOT EXISTS )?\"?$table\"?\\b", RegexOption.IGNORE_CASE).findAll(sql).count() shouldBe 1
                }
            }
        }

        test("a transformer that does not consume UML class diagrams fails with a clear message") {
            val project = newProject(scripts = splitScripts, kumlBlock = """transformer.set("erm-to-exposed")""")
            withProject(project) {
                val output = runner(project = it, args = listOf("kumlTransform")).buildAndFail().output
                output shouldContain "does not accept a merged UML class diagram"
                output shouldContain "kumlTransform: transformer 'erm-to-exposed'"
            }
        }

        test("kumlGenerate with mergeScripts merges cross-script plain-class stubs") {
            val domainA =
                "classDiagram(name = \"A\") {\n classOf(name = \"Address\") {\n" +
                    " attribute(name = \"street\", type = \"String\")\n attribute(name = \"city\", type = \"String\")\n }\n}\n"
            val domainB =
                "classDiagram(name = \"B\") {\n classOf(name = \"Address\") {\n" +
                    " attribute(name = \"street\", type = \"String\")\n }\n}\n"
            val project =
                newProject(
                    scripts = mapOf("a.kuml.kts" to domainA, "b.kuml.kts" to domainB),
                    kumlBlock =
                        """generator.set("kotlin")
                           mergeScripts.set(true)""",
                )
            withProject(project) {
                val result = runner(project = it, args = listOf("kumlGenerate")).build()
                result.output shouldContain "-> 1 file(s)"
                val generatedDir = it.resolve("build/kuml/generated")
                val address = generatedDir.walkTopDown().filter { f -> f.name == "Address.kt" }.toList()
                address.size shouldBe 1
                address.single().readText() shouldContain "city"
            }
        }
    })
