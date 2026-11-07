package dev.kuml.workspace.pdf

import dev.kuml.workspace.pdf.source.ImageFormat
import dev.kuml.workspace.pdf.source.WorkspaceImageFiles
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class WorkspaceImageFilesTest :
    FunSpec({
        fun image(
            file: File,
            format: String,
            w: Int = 8,
            h: Int = 8,
        ) {
            file.parentFile.mkdirs()
            ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, file)
        }

        fun fixture(): File {
            val root = TestSupport.tempDir()
            image(file = File(root, "img/a.png"), format = "png")
            image(file = File(root, "img/disguised.png"), format = "jpg")
            File(root, "img/text.png").writeText("not an image")
            image(file = File(root, "img/huge.png"), format = "png", w = 9000, h = 2)
            File(root, "index.md").writeText("# x")
            return root
        }

        fun resolve(
            root: File,
            dest: String,
        ) = WorkspaceImageFiles.resolve(root = root, from = null, destination = dest)

        test("a plain relative PNG resolves and probes with the format of its content") {
            val root = fixture()
            val f = resolve(root, "img/a.png").shouldNotBeNull()
            val img = WorkspaceImageFiles.probe(file = f, maxBytes = 1_000_000, maxPixels = 8_000).shouldNotBeNull()
            img.format shouldBe ImageFormat.PNG
            img.widthPx shouldBe 8
        }

        test("the format comes from the content, not from the extension") {
            val root = fixture()
            val f = resolve(root, "img/disguised.png").shouldNotBeNull()
            WorkspaceImageFiles.probe(file = f, maxBytes = 1_000_000, maxPixels = 8_000)!!.format shouldBe ImageFormat.JPEG
        }

        test("a text file with an image extension is not an image") {
            val root = fixture()
            WorkspaceImageFiles.probe(file = resolve(root, "img/text.png")!!, maxBytes = 1_000_000, maxPixels = 8_000).shouldBeNull()
        }

        test("an image over the pixel limit is refused before decoding") {
            val root = fixture()
            WorkspaceImageFiles.probe(file = resolve(root, "img/huge.png")!!, maxBytes = 10_000_000, maxPixels = 8_000).shouldBeNull()
        }

        test("an image over the byte limit is refused") {
            val root = fixture()
            WorkspaceImageFiles.probe(file = resolve(root, "img/a.png")!!, maxBytes = 10, maxPixels = 8_000).shouldBeNull()
        }

        test("escaping and remote destinations are refused") {
            val root = fixture()
            listOf(
                "../x.png",
                "img/../../x.png",
                "/etc/passwd",
                "C:\\Windows\\x.png",
                "C:/x.png",
                "http://example.org/a.png",
                "https://example.org/a.png",
                "data:image/png;base64,AAAA",
                "file:///etc/passwd",
                "img\\a.png",
                "",
            ).forEach { resolve(root, it).shouldBeNull() }
        }

        test("a query or fragment suffix is ignored") {
            resolve(fixture(), "img/a.png?x=1#y").shouldNotBeNull()
        }

        test("a symbolic link to a file outside the root is refused") {
            val root = fixture()
            val outside = TestSupport.tempDir()
            image(file = File(outside, "secret.png"), format = "png")
            runCatching { Files.createSymbolicLink(File(root, "img/link.png").toPath(), File(outside, "secret.png").toPath()) }.onSuccess {
                resolve(root, "img/link.png").shouldBeNull()
            }
        }

        test("a symbolic link as a path component is refused") {
            val root = fixture()
            val outside = TestSupport.tempDir()
            image(file = File(outside, "secret.png"), format = "png")
            runCatching { Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath()) }.onSuccess {
                resolve(root, "linked/secret.png").shouldBeNull()
            }
        }
    })
