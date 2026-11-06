package dev.kuml.desktop.ui.menu

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.kuml.desktop.ui.KumlIcons
import dev.kuml.desktop.ui.pathFingerprint
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

class MainMenuIconsTest :
    FunSpec({

        val withIcon = MenuEntryId.entries.filter { it !in MainMenuIcons.INTENTIONALLY_WITHOUT_ICON }

        test("every entry has an icon XOR is on the explicit exception list") {
            MenuEntryId.entries.forEach { id ->
                withClue(id.name) {
                    (MainMenuIcons.iconFor(id) != null) shouldBe (id !in MainMenuIcons.INTENTIONALLY_WITHOUT_ICON)
                }
            }
            MainMenuIcons.INTENTIONALLY_WITHOUT_ICON.toList() shouldContainExactlyInAnyOrder
                listOf(
                    MenuEntryId.VIEW_THEME_OPTION,
                    MenuEntryId.VIEW_LANGUAGE_DE,
                    MenuEntryId.VIEW_LANGUAGE_EN,
                    MenuEntryId.VIEW_WATERMARK,
                    MenuEntryId.FILE_RECENT_ENTRY,
                    MenuEntryId.FILE_RECENT_EMPTY,
                    MenuEntryId.FILE_RECENT_CLEAR,
                )
            withIcon.size shouldBe 29
        }

        /** Renders [painter] into a 16x16 bitmap and returns the alpha values (0..255) of all pixels. */
        fun renderAlphas(painter: Painter): List<Int> {
            val bitmap = ImageBitmap(16, 16)
            val canvas = Canvas(bitmap)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(16f, 16f)) {
                with(painter) { draw(size = size) }
            }
            val sk = bitmap.asSkiaBitmap()
            return (0 until 16).flatMap { y -> (0 until 16).map { x -> (sk.getColor(x, y) ushr 24) and 0xFF } }
        }

        @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
        fun painterFor(
            id: MenuEntryId,
            enabled: Boolean,
        ): Painter? {
            var painter: Painter? = null
            runComposeUiTest { setContent { painter = menuIcon(id = id, enabled = enabled) } }
            return painter
        }

        test("every icon renders headless at 16x16 and is dimmed when disabled") {
            withIcon.forEach { id ->
                withClue(id.name) {
                    val on = painterFor(id, enabled = true)!!
                    on.intrinsicSize shouldBe Size(16f, 16f)
                    renderAlphas(on).max() shouldBeGreaterThan 150
                    val off = painterFor(id, enabled = false)!!
                    (renderAlphas(off).max() / 255f) shouldBeLessThan 0.4f
                    renderAlphas(off).max() shouldNotBe 0
                }
            }
        }

        test("entries without an icon yield no painter") {
            MainMenuIcons.INTENTIONALLY_WITHOUT_ICON.forEach { id ->
                withClue(id.name) { painterFor(id, enabled = true) shouldBe null }
            }
        }

        test("MainWindow wires every icon entry exactly once and no exception entry") {
            val candidates =
                listOf(
                    File(System.getProperty("user.dir"), "src/jvmMain/kotlin/dev/kuml/desktop/MainWindow.kt"),
                    File(System.getProperty("user.dir"), "kuml-desktop/src/jvmMain/kotlin/dev/kuml/desktop/MainWindow.kt"),
                )
            val file = candidates.firstOrNull { it.isFile }
            withClue("MainWindow.kt not found relative to user.dir=${System.getProperty("user.dir")}") { (file != null) shouldBe true }
            val source = file!!.readText()
            // Submenu headers have no Compose icon parameter; they are wired through the Swing interop helper.
            val submenuIds = setOf(MenuEntryId.FILE_EXPORT_WORKSPACE_MENU, MenuEntryId.FILE_RECENT_MENU)
            withIcon.forEach { id ->
                val callee = if (id in submenuIds) "menuIconToSwing" else "menuIcon"
                withClue("$callee(id = MenuEntryId.${id.name}") {
                    Regex("""$callee\(\s*id\s*=\s*MenuEntryId\.${id.name}\b""").findAll(source).count() shouldBe 1
                }
            }
            MainMenuIcons.INTENTIONALLY_WITHOUT_ICON.forEach { id ->
                withClue(id.name) { source.contains("MenuEntryId.${id.name}") shouldBe false }
            }
        }

        test("the submenu headers have their own symbols") {
            MainMenuIcons.iconFor(MenuEntryId.FILE_EXPORT_WORKSPACE_MENU) shouldBe KumlIcons.Workspace
            MainMenuIcons.iconFor(MenuEntryId.FILE_RECENT_MENU) shouldBe KumlIcons.History
            (MenuEntryId.FILE_EXPORT_WORKSPACE_MENU in MainMenuIcons.INTENTIONALLY_WITHOUT_ICON) shouldBe false
            (MenuEntryId.FILE_RECENT_MENU in MainMenuIcons.INTENTIONALLY_WITHOUT_ICON) shouldBe false
        }

        test("easily confused icons have different path data") {
            fun data(icon: androidx.compose.ui.graphics.vector.ImageVector): String = pathFingerprint(icon)
            listOf(
                listOf(KumlIcons.Search, KumlIcons.ZoomIn, KumlIcons.ZoomOut),
                listOf(KumlIcons.Save, KumlIcons.SaveAs),
                listOf(KumlIcons.History, KumlIcons.Workspace, KumlIcons.FolderOpen),
                listOf(KumlIcons.Undo, KumlIcons.Redo, KumlIcons.SimReset),
            ).forEach { group ->
                group.map { data(it) }.toSet().size shouldBe group.size
            }
        }
    })
