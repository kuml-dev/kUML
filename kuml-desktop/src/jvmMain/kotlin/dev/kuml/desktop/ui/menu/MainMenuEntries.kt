package dev.kuml.desktop.ui.menu

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import dev.kuml.desktop.ui.KumlIcons

/**
 * Identifies every entry of the native main menu bar that is relevant for icons. Dynamic groups
 * (one radio per theme, one item per recent file) are represented by one id each.
 */
enum class MenuEntryId {
    FILE_NEW,
    FILE_OPEN,
    FILE_NEW_WORKSPACE,
    FILE_OPEN_WORKSPACE,
    FILE_CLOSE_WORKSPACE,
    FILE_SAVE,
    FILE_SAVE_AS,
    FILE_EXPORT_SVG,
    FILE_EXPORT_PNG,
    FILE_EXPORT_WORKSPACE_MENU,
    FILE_EXPORT_WORKSPACE_PDF,
    FILE_RECENT_MENU,
    FILE_QUIT,
    EDIT_UNDO,
    EDIT_REDO,
    EDIT_FIND,
    VIEW_MODE_SOURCE,
    VIEW_MODE_SPLIT,
    VIEW_MODE_DIAGRAM,
    HELP_DOCS,
    HELP_ABOUT,
    AI_PROVIDER_SETTINGS,
    AI_PANEL,
    AI_NEW_SESSION,
    TOOLS_SIMULATE,
    TOOLS_STOP_SIMULATION,
    TOOLS_SIM_INSPECTOR,
    TOOLS_MCP_SERVER,
    TOOLS_PLUGIN_MANAGER,

    // Intentionally without an icon: selection lists and dynamic groups.
    VIEW_THEME_OPTION,
    VIEW_LANGUAGE_DE,
    VIEW_LANGUAGE_EN,
    VIEW_WATERMARK,
    FILE_RECENT_ENTRY,
    FILE_RECENT_EMPTY,
    FILE_RECENT_CLEAR,
}

object MainMenuIcons {
    /** Entries that deliberately stay text-only (selection lists, toggles with a check mark, dynamic groups). */
    val INTENTIONALLY_WITHOUT_ICON: Set<MenuEntryId> =
        setOf(
            MenuEntryId.VIEW_THEME_OPTION,
            MenuEntryId.VIEW_LANGUAGE_DE,
            MenuEntryId.VIEW_LANGUAGE_EN,
            MenuEntryId.VIEW_WATERMARK,
            MenuEntryId.FILE_RECENT_ENTRY,
            MenuEntryId.FILE_RECENT_EMPTY,
            MenuEntryId.FILE_RECENT_CLEAR,
        )

    /** Exhaustive on purpose: a new [MenuEntryId] fails to compile until it is mapped. */
    fun iconFor(id: MenuEntryId): ImageVector? =
        when (id) {
            MenuEntryId.FILE_NEW -> KumlIcons.DocumentNew
            MenuEntryId.FILE_OPEN -> KumlIcons.FolderOpen
            MenuEntryId.FILE_NEW_WORKSPACE -> KumlIcons.WorkspaceNew
            MenuEntryId.FILE_OPEN_WORKSPACE -> KumlIcons.Workspace
            MenuEntryId.FILE_CLOSE_WORKSPACE -> KumlIcons.Close
            MenuEntryId.FILE_SAVE -> KumlIcons.Save
            MenuEntryId.FILE_SAVE_AS -> KumlIcons.SaveAs
            MenuEntryId.FILE_EXPORT_SVG -> KumlIcons.SaveSvg
            MenuEntryId.FILE_EXPORT_PNG -> KumlIcons.SavePng
            MenuEntryId.FILE_EXPORT_WORKSPACE_MENU -> KumlIcons.Workspace
            MenuEntryId.FILE_EXPORT_WORKSPACE_PDF -> KumlIcons.PdfDocument
            MenuEntryId.FILE_RECENT_MENU -> KumlIcons.History
            MenuEntryId.FILE_QUIT -> KumlIcons.Quit
            MenuEntryId.EDIT_UNDO -> KumlIcons.Undo
            MenuEntryId.EDIT_REDO -> KumlIcons.Redo
            MenuEntryId.EDIT_FIND -> KumlIcons.Search
            MenuEntryId.VIEW_MODE_SOURCE -> KumlIcons.ViewSource
            MenuEntryId.VIEW_MODE_SPLIT -> KumlIcons.ViewSplit
            MenuEntryId.VIEW_MODE_DIAGRAM -> KumlIcons.ViewDiagram
            MenuEntryId.HELP_DOCS -> KumlIcons.Help
            MenuEntryId.HELP_ABOUT -> KumlIcons.Info
            MenuEntryId.AI_PROVIDER_SETTINGS -> KumlIcons.Settings
            MenuEntryId.AI_PANEL -> KumlIcons.Ai
            MenuEntryId.AI_NEW_SESSION -> KumlIcons.ChatNew
            MenuEntryId.TOOLS_SIMULATE -> KumlIcons.SimStart
            MenuEntryId.TOOLS_STOP_SIMULATION -> KumlIcons.SimStop
            MenuEntryId.TOOLS_SIM_INSPECTOR -> KumlIcons.SimInspector
            MenuEntryId.TOOLS_MCP_SERVER -> KumlIcons.Server
            MenuEntryId.TOOLS_PLUGIN_MANAGER -> KumlIcons.Plugins
            MenuEntryId.VIEW_THEME_OPTION,
            MenuEntryId.VIEW_LANGUAGE_DE,
            MenuEntryId.VIEW_LANGUAGE_EN,
            MenuEntryId.VIEW_WATERMARK,
            MenuEntryId.FILE_RECENT_ENTRY,
            MenuEntryId.FILE_RECENT_EMPTY,
            MenuEntryId.FILE_RECENT_CLEAR,
            -> null
        }
}

private const val MENU_ICON_SIZE_PX = 16f
private const val DISABLED_ALPHA = 0.35f
private val LIGHT_MENU_FOREGROUND = Color(0xFF2B2B2B)
private val DARK_MENU_FOREGROUND = Color(0xFFE0E0E0)

/**
 * Tint of a menu icon. macOS draws the menu bar in the system appearance (not the app theme), so the
 * system dark flag decides; elsewhere the Swing look-and-feel menu foreground is used. Disabled
 * entries are dimmed on the painter itself because the native menu only dims the text.
 */
internal fun menuIconTint(
    isMac: Boolean,
    systemDark: Boolean,
    swingColor: java.awt.Color?,
    enabled: Boolean,
): Color {
    val base =
        if (isMac) {
            if (systemDark) DARK_MENU_FOREGROUND else LIGHT_MENU_FOREGROUND
        } else {
            swingColor?.let { Color(it.red, it.green, it.blue, it.alpha) } ?: LIGHT_MENU_FOREGROUND
        }
    return if (enabled) base else base.copy(alpha = DISABLED_ALPHA)
}

/**
 * Menu icon painter for [id], or `null` for entries without an icon. The intrinsic size is a fixed
 * 16x16 px because the native menu converts the painter to a Swing icon using this size.
 */
@Composable
fun menuIcon(
    id: MenuEntryId,
    enabled: Boolean = true,
): Painter? {
    val vector = MainMenuIcons.iconFor(id) ?: return null
    val systemDark = isSystemInDarkTheme()
    val delegate = rememberVectorPainter(vector)
    val isMac = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)
    val swingColor = if (isMac) null else javax.swing.UIManager.getColor("Menu.foreground")
    val tint = menuIconTint(isMac = isMac, systemDark = systemDark, swingColor = swingColor, enabled = enabled)
    return remember(delegate, tint) { TintedMenuIconPainter(delegate = delegate, tint = tint) }
}

/**
 * Swing icon for a submenu header. Compose's `Menu` has no `icon` parameter, so the submenu symbols are set
 * on the underlying `JMenu` through [applySubmenuIcons]; this renders the same vector, size and tint as the
 * item icons.
 */
@Composable
fun menuIconToSwing(
    id: MenuEntryId,
    enabled: Boolean = true,
): javax.swing.Icon? {
    val painter = menuIcon(id = id, enabled = enabled) ?: return null
    val density = LocalDensity.current
    return remember(painter, density) {
        val px = (MENU_ICON_SIZE_PX * density.density).toInt().coerceAtLeast(1)
        val bitmap = ImageBitmap(px, px)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
            with(painter) { draw(size = size) }
        }
        javax.swing.ImageIcon(bitmap.toAwtImage())
    }
}

private class TintedMenuIconPainter(
    private val delegate: Painter,
    private val tint: Color,
) : Painter() {
    override val intrinsicSize: Size = Size(MENU_ICON_SIZE_PX, MENU_ICON_SIZE_PX)

    override fun DrawScope.onDraw() {
        with(delegate) { draw(size = size, alpha = 1f, colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn)) }
    }
}
