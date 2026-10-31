package dev.kuml.desktop.workspace

import dev.kuml.workspace.pdf.ExportScope
import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PageStrategy
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

        test("automatic page size forces automatic orientation and disables the orientation choice with a reason") {
            val s = PdfExportFormState(pageStrategy = PageStrategy.FIXED, orientation = PageOrientation.LANDSCAPE)
            s.availability(PdfOption.ORIENTATION) shouldBe PdfOptionAvailability.Enabled
            val auto = s.withPageStrategy(PageStrategy.AUTO)
            auto.orientation shouldBe PageOrientation.AUTO
            auto.availability(PdfOption.ORIENTATION) shouldBe PdfOptionAvailability.Disabled(PdfReason.ORIENTATION_BY_PAGE_STRATEGY)
            auto.toOptions(producerVersion = "p").pageStrategy shouldBe PageStrategy.AUTO
            // switching back to fixed keeps the orientation automatic until the user changes it
            auto.withPageStrategy(PageStrategy.FIXED).orientation shouldBe PageOrientation.AUTO
        }

        test("toOptions never throws on an inconsistent form and passes the layout fields through") {
            val inconsistent = PdfExportFormState(pageStrategy = PageStrategy.AUTO, orientation = PageOrientation.PORTRAIT)
            inconsistent.toOptions(producerVersion = "p").orientation shouldBe PageOrientation.AUTO
            val o = PdfExportFormState(tiling = false, minScalePercent = 65).toOptions(producerVersion = "p")
            o.tiling shouldBe false
            o.minScale shouldBe 0.65f
            o.pageStrategy shouldBe PageStrategy.AUTO
            PdfExportFormState(minScalePercent = 5).toOptions(producerVersion = "p").minScale shouldBe 0.25f
            PdfExportFormState(minScalePercent = 400).toOptions(producerVersion = "p").minScale shouldBe 1.0f
        }

        test("min scale percent is clamped and stepped by five") {
            PdfExportFormState().withMinScalePercent(10).minScalePercent shouldBe 25
            PdfExportFormState().withMinScalePercent(200).minScalePercent shouldBe 100
            PdfExportFormState().withMinScalePercent(53).minScalePercent shouldBe 50
        }
    })
