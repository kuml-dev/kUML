package dev.kuml.desktop.workspace

import dev.kuml.workspace.pdf.ExportScope
import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PdfExportKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PdfExportFormTest :
    FunSpec({
        test("default state is the handbook preset with cover and table of contents") {
            val o = PdfExportFormState().toOptions(producerVersion = "kUML x")
            o.kind shouldBe PdfExportKind.HANDBOOK
            o.cover shouldBe true
            o.toc shouldBe true
            o.page shouldBe PageFormat.A4
            o.orientation shouldBe PageOrientation.AUTO
            o.scope shouldBe ExportScope.All
            o.producerVersion shouldBe "kUML x"
        }

        test("per-document preset switches the cover off by default") {
            val s = PdfExportFormState().withPreset(PdfPreset.PER_DOCUMENT)
            s.cover shouldBe false
            s.toOptions(producerVersion = "p").kind shouldBe PdfExportKind.PER_DOCUMENT
        }

        test("inapplicable options are disabled with a reason, not hidden, and are not applied") {
            val s = PdfExportFormState(toc = true, cover = true).withPreset(PdfPreset.DIAGRAMS).copy(cover = true)
            s.availability(PdfOption.TOC) shouldBe PdfOptionAvailability.Disabled(PdfReason.TOC_FOR_DIAGRAMS)
            s.availability(PdfOption.COVER) shouldBe PdfOptionAvailability.Disabled(PdfReason.COVER_FOR_DIAGRAMS)
            s.availability(PdfOption.SPLIT) shouldBe PdfOptionAvailability.Enabled
            val o = s.toOptions(producerVersion = "p")
            o.toc shouldBe false
            o.cover shouldBe false
            PdfExportFormState().availability(PdfOption.SPLIT) shouldBe PdfOptionAvailability.Disabled(PdfReason.SPLIT_NOT_DIAGRAMS)
            PdfExportFormState(split = true).toOptions(producerVersion = "p").split shouldBe false
        }

        test("folder scope and metadata map to options; invalid input blocks submit") {
            val s = PdfExportFormState(folderScope = true, folder = " articles ", author = " Eva ", version = "2", date = "2026-10-28")
            val o = s.toOptions(producerVersion = "p")
            o.scope shouldBe ExportScope.Folder(relativePath = "articles")
            o.metadata.author shouldBe "Eva"
            o.metadata.date.toString() shouldBe "2026-10-28"
            PdfExportFormState(date = "yesterday").canSubmit shouldBe false
            PdfExportFormState(folderScope = true, folder = "").canSubmit shouldBe false
            PdfExportFormState(folderScope = true, folder = "../x").canSubmit shouldBe false
        }

        test("the A3 landscape suggestion is applied to the form") {
            val s = PdfExportFormState().withA3Landscape()
            s.page shouldBe PageFormat.A3
            s.orientation shouldBe PageOrientation.LANDSCAPE
        }
    })
