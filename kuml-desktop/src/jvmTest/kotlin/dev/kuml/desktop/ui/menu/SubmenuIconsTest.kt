package dev.kuml.desktop.ui.menu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem

class SubmenuIconsTest :
    FunSpec({

        fun icon() = ImageIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))

        fun fileMenu(
            title: String,
            vararg submenus: String,
        ): JMenu =
            JMenu(title).apply {
                add(JMenuItem("Open"))
                submenus.forEach { add(JMenu(it).apply { add(JMenuItem("child")) }) }
            }

        test("sets the icons on the matching direct submenus of the file menu") {
            val bar = JMenuBar().apply { add(fileMenu("File", "Export Workspace", "Open Recent")) }
            val export = icon()
            val recent = icon()
            val applied =
                applySubmenuIcons(
                    menuBar = bar,
                    topMenuText = "File",
                    icons =
                        mapOf(
                            "Export Workspace" to export,
                            "Open Recent" to recent,
                        ),
                )
            applied shouldBe 2
            val submenus = bar.getMenu(0).menuComponents.filterIsInstance<JMenu>()
            submenus[0].icon shouldBe export
            submenus[1].icon shouldBe recent
            (bar.getMenu(0).menuComponents[0] as JMenuItem).icon shouldBe null
        }

        test("a wrong top-level menu text leaves everything untouched") {
            val bar = JMenuBar().apply { add(fileMenu("File", "Open Recent")) }
            applySubmenuIcons(menuBar = bar, topMenuText = "Datei", icons = mapOf("Open Recent" to icon())) shouldBe 0
            bar
                .getMenu(0)
                .menuComponents
                .filterIsInstance<JMenu>()
                .single()
                .icon shouldBe null
        }

        test("a null menu bar is ignored") {
            applySubmenuIcons(menuBar = null, topMenuText = "File", icons = mapOf("Open Recent" to icon())) shouldBe 0
        }

        test("a language switch applies the icons again under the new texts") {
            val bar = JMenuBar().apply { add(fileMenu("Datei", "Zuletzt geöffnet")) }
            val recent = icon()
            applySubmenuIcons(menuBar = bar, topMenuText = "File", icons = mapOf("Open Recent" to recent)) shouldBe 0
            applySubmenuIcons(menuBar = bar, topMenuText = "Datei", icons = mapOf("Zuletzt geöffnet" to recent)) shouldBe 1
            bar
                .getMenu(0)
                .menuComponents
                .filterIsInstance<JMenu>()
                .single()
                .icon shouldBe recent
        }

        test("applying twice is idempotent") {
            val bar = JMenuBar().apply { add(fileMenu("File", "Open Recent")) }
            val recent = icon()
            applySubmenuIcons(menuBar = bar, topMenuText = "File", icons = mapOf("Open Recent" to recent)) shouldBe 1
            applySubmenuIcons(menuBar = bar, topMenuText = "File", icons = mapOf("Open Recent" to recent)) shouldBe 0
        }

        test("a submenu with the same text outside the file menu is not touched") {
            val other = JMenu("Edit").apply { add(JMenu("Open Recent").apply { add(JMenuItem("x")) }) }
            val bar =
                JMenuBar().apply {
                    add(fileMenu("File", "Open Recent"))
                    add(other)
                }
            applySubmenuIcons(menuBar = bar, topMenuText = "File", icons = mapOf("Open Recent" to icon())) shouldBe 1
            other.menuComponents
                .filterIsInstance<JMenu>()
                .single()
                .icon shouldBe null
        }

        test("only direct children are considered, not nested submenus") {
            val nested = JMenu("Outer").apply { add(JMenu("Open Recent").apply { add(JMenuItem("x")) }) }
            val file = JMenu("File").apply { add(nested) }
            val bar = JMenuBar().apply { add(file) }
            applySubmenuIcons(menuBar = bar, topMenuText = "File", icons = mapOf("Open Recent" to icon())) shouldBe 0
        }
    })
