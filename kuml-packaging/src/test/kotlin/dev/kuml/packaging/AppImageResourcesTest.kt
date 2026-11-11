package dev.kuml.packaging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/**
 * Static and stub-based checks of the AppImage packaging files. The real appimagetool only runs in CI
 * (release-installers.yml); here a fake appimagetool records its arguments so regressions in the script
 * fail `check` instead of the release pipeline.
 */
class AppImageResourcesTest :
    StringSpec({

        val dir = File("src/main/appimage")
        val script = File(dir, "build-appimage.sh")
        val posix = !System.getProperty("os.name").lowercase().contains("win")

        "AppRun is an executable sh script that execs the jpackage launcher" {
            val file = File(dir, "AppRun")
            file.exists().shouldBeTrue()
            file.canExecute().shouldBeTrue()
            val text = file.readText()
            text shouldStartWith "#!/bin/sh"
            text shouldContain "exec \"\$HERE/opt/kuml-desktop/bin/kuml-desktop\" \"\$@\""
        }

        "desktop template has the expected keys and version placeholder" {
            val text = File(dir, "kuml-desktop.desktop").readText()
            text shouldContain "Exec=kuml-desktop"
            text shouldContain "Icon=kuml-desktop"
            text shouldContain "X-AppImage-Version=@VERSION@"
        }

        class Run(
            val code: Int,
            val out: String,
        )

        fun run(
            appDir: File,
            icon: File,
            version: String,
            out: File,
            tool: File,
        ): Run {
            val pb =
                ProcessBuilder("bash", script.absolutePath, appDir.absolutePath, icon.absolutePath, version, out.absolutePath)
                    .redirectErrorStream(true)
            pb.environment()["APPIMAGETOOL"] = tool.absolutePath
            pb.environment()["APPIMAGE_RUNTIME"] = "/fake/runtime"
            val p = pb.start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor(60, TimeUnit.SECONDS)
            return Run(p.exitValue(), text)
        }

        fun fixture(withLauncher: Boolean): Triple<File, File, File> {
            val root = Files.createTempDirectory("appimage-test").toFile()
            val app = File(root, "app").also { File(it, "bin").mkdirs() }
            if (withLauncher) {
                val l = File(app, "bin/kuml-desktop")
                l.writeText("#!/bin/sh\necho hi\n")
                l.setExecutable(true)
            }
            val icon = File(root, "icon.png").also { it.writeText("png") }
            val tool = File(root, "appimagetool")
            tool.writeText(
                """
                #!/bin/sh
                # stub: record args + env, copy the AppDir for inspection, create the output
                echo "ARCH=${'$'}ARCH" > "${'$'}0.log"
                echo "ARGS=${'$'}*" >> "${'$'}0.log"
                for a in "${'$'}@"; do last_dir="${'$'}prev"; prev="${'$'}a"; done
                cp -R "${'$'}last_dir" "${'$'}0.appdir"
                touch "${'$'}prev"
                """.trimIndent() + "\n",
            )
            tool.setExecutable(true)
            return Triple(app, icon, tool)
        }

        "build-appimage.sh assembles the AppDir and invokes appimagetool".config(enabled = posix) {
            val (app, icon, tool) = fixture(true)
            val out = File(tool.parentFile, "out/kuml.AppImage")
            val r = run(app, icon, "1.2.3-rc.1", out, tool)
            r.code shouldBe 0
            out.exists().shouldBeTrue()
            val log = File(tool.absolutePath + ".log").readText()
            log shouldContain "ARCH=x86_64"
            log shouldContain "--runtime-file /fake/runtime"
            val appdir = File(tool.absolutePath + ".appdir")
            Files.getPosixFilePermissions(File(appdir, "AppRun").toPath()).contains(PosixFilePermission.OWNER_EXECUTE).shouldBeTrue()
            File(appdir, "opt/kuml-desktop/bin/kuml-desktop").exists().shouldBeTrue()
            File(appdir, "kuml-desktop.png").exists().shouldBeTrue()
            Files.isSymbolicLink(File(appdir, ".DirIcon").toPath()).shouldBeTrue()
            File(appdir, "usr/share/icons/hicolor/512x512/apps/kuml-desktop.png").exists().shouldBeTrue()
            val desktop = File(appdir, "kuml-desktop.desktop").readText()
            desktop shouldContain "X-AppImage-Version=1.2.3-rc.1"
            desktop.contains("@VERSION@") shouldBe false
        }

        "build-appimage.sh rejects invalid versions".config(enabled = posix) {
            val (app, icon, tool) = fixture(true)
            val out = File(tool.parentFile, "o.AppImage")
            for (v in listOf("v1.2.3", "1.2.3;rm")) {
                val r = run(app, icon, v, out, tool)
                r.code shouldNotBe 0
                r.out shouldContain "invalid version"
            }
            out.exists() shouldBe false
        }

        "build-appimage.sh rejects a missing launcher".config(enabled = posix) {
            val (app, icon, tool) = fixture(false)
            val r = run(app, icon, "1.2.3", File(tool.parentFile, "o.AppImage"), tool)
            r.code shouldNotBe 0
            r.out shouldContain "missing launcher"
        }
    })
