package dev.kuml.desktop.ui.menu

import javax.swing.Icon
import javax.swing.JMenu
import javax.swing.JMenuBar

/**
 * Sets [icons] on the direct submenu children of the top-level menu titled [topMenuText].
 *
 * Compose's native `Menu` has no `icon` parameter (only `Item` does), so a submenu header such as
 * "Open Recent" would otherwise stay text-only and its text would not line up with the iconed siblings.
 * The map is keyed by the submenu's text; unknown top-level titles and unmatched texts are left untouched.
 * Pure Swing logic, so it is testable headless. Call on the event-dispatch thread.
 *
 * @return the number of submenus an icon was set on
 */
fun applySubmenuIcons(
    menuBar: JMenuBar?,
    topMenuText: String,
    icons: Map<String, Icon?>,
): Int {
    if (menuBar == null) return 0
    var applied = 0
    for (index in 0 until menuBar.menuCount) {
        val top = menuBar.getMenu(index) ?: continue
        if (top.text != topMenuText) continue
        for (child in top.menuComponents) {
            if (child !is JMenu) continue
            val icon = icons[child.text] ?: continue
            if (child.icon !== icon) {
                child.icon = icon
                applied++
            }
        }
    }
    return applied
}
