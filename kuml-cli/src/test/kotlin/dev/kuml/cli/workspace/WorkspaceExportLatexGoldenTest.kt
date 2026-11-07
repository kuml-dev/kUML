package dev.kuml.cli.workspace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import dev.kuml.layout.LayoutEngineRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the exact stdout, stderr and exit code of `kuml workspace export --format latex`, so that the logic shared
 * with the Desktop dialog (`WorkspaceLatexRun`) cannot change the CLI's output. Set `KUML_GOLDEN_RECORD=1` to
 * (re)record the golden files under `src/test/resources/latex-golden/`.
 */
class WorkspaceExportLatexGoldenTest :
    FunSpec({
        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        val res = File("src/test/resources")
        val record = System.getenv("KUML_GOLDEN_RECORD") == "1"

        fun knowledge(): File {
            val root = Files.createTempDirectory("kuml-golden-k").toFile()
            File(res, "latex-knowledge").copyRecursively(root)
            return root
        }

        fun engineering(): File {
            val root = Files.createTempDirectory("kuml-golden-e").toFile()
            File(res, "latex/uml-class.kuml.kts").copyTo(File(root, "a.kuml.kts"))
            File(res, "latex-folder/erm.kuml.kts").copyTo(File(root, "erm.kuml.kts"))
            File(root, "broken.kuml.kts").writeText("this is not kotlin (")
            return root
        }

        fun run(
            root: File,
            vararg args: String,
        ): String {
            val r = KumlCli().test(listOf("workspace", "export", root.absolutePath, "--format", "latex") + args.toList())
            val raw = "exit=${r.statusCode}\n--stdout--\n${r.stdout}--stderr--\n${r.stderr}"
            return raw
                .replace(root.canonicalPath, "<ROOT>")
                .replace(root.absolutePath, "<ROOT>")
        }

        fun check(
            name: String,
            actual: String,
        ) {
            val golden = File(res, "latex-golden/$name.txt")
            if (record) {
                golden.parentFile.mkdirs()
                golden.writeText(actual)
            } else {
                actual shouldBe golden.readText()
            }
        }

        fun scenarios(
            prefix: String,
            make: () -> File,
        ) {
            test("$prefix: plain, second run refused, --force, --strict").config(timeout = 400.seconds) {
                val root = make()
                try {
                    val out = File(root, "out").absolutePath
                    check("$prefix-plain", run(root, "-o", out))
                    check("$prefix-exists", run(root, "-o", out))
                    check("$prefix-force", run(root, "-o", out, "--force"))
                    val strictOut = File(root, "strict-out").absolutePath
                    check("$prefix-strict", run(root, "-o", strictOut, "--strict"))
                    File(strictOut).exists() shouldBe false
                } finally {
                    root.deleteRecursively()
                }
            }
        }

        test("engineering: nothing exportable").config(timeout = 400.seconds) {
            val root = Files.createTempDirectory("kuml-golden-n").toFile()
            try {
                File(root, "broken.kuml.kts").writeText("this is not kotlin (")
                check("engineering-nothing", run(root, "-o", File(root, "out").absolutePath))
            } finally {
                root.deleteRecursively()
            }
        }

        scenarios("knowledge", ::knowledge)
        scenarios("engineering", ::engineering)
    })
