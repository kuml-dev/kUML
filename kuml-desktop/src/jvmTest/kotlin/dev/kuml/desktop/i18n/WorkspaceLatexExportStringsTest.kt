package dev.kuml.desktop.i18n

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank

class WorkspaceLatexExportStringsTest :
    FunSpec({
        val fields = WorkspaceLatexExportStrings::class.java.declaredFields.filter { it.type == String::class.java }

        fun value(
            s: WorkspaceLatexExportStrings,
            name: String,
        ): String = fields.first { it.name == name }.also { it.isAccessible = true }.get(s) as String

        test("every text exists in German and English and differs between them") {
            fields.forEach { f ->
                withClue(f.name) {
                    value(Strings.EN.workspaceLatexExport, f.name).shouldNotBeBlank()
                    value(Strings.DE.workspaceLatexExport, f.name).shouldNotBeBlank()
                }
            }
            Strings.EN.workspaceLatexExport.title shouldNotBe Strings.DE.workspaceLatexExport.title
        }

        test("format placeholders agree between the two languages") {
            val placeholder = Regex("""%(\d+\$)?[sd]""")
            fields.forEach { f ->
                withClue(f.name) {
                    val en = placeholder.findAll(value(Strings.EN.workspaceLatexExport, f.name)).map { it.value }.toList()
                    val de = placeholder.findAll(value(Strings.DE.workspaceLatexExport, f.name)).map { it.value }.toList()
                    de shouldBe en
                }
            }
        }

        test("the format strings accept the arguments the dialog passes") {
            listOf(Strings.EN, Strings.DE).forEach { st ->
                val s = st.workspaceLatexExport
                s.overwriteHeadline.format(2, "a, b").shouldNotBeBlank()
                s.doneHeadline.format("/x").shouldNotBeBlank()
                s.failedHeadline.format("r").shouldNotBeBlank()
                s.ioError.format("IOException").shouldNotBeBlank()
                s.openFolderFailed.format("IOException").shouldNotBeBlank()
                s.moreLines.format(3).shouldNotBeBlank()
            }
        }
    })
