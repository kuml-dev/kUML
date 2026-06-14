package dev.kuml.desktop.i18n

/**
 * Lokalisierte UI-Strings für die kUML Desktop App.
 * EN ist der Default-Fallback; DE explizit wählbar.
 */
data class Strings(
    val menuFile: String,
    val menuFileNew: String,
    val menuFileOpen: String,
    val menuFileSave: String,
    val menuFileSaveAs: String,
    val menuFileQuit: String,
    val menuEdit: String,
    val menuEditUndo: String,
    val menuEditRedo: String,
    val menuEditFind: String,
    val menuView: String,
    val menuViewTheme: String,
    val menuViewLanguage: String,
    val menuHelp: String,
    val menuHelpDocs: String,
    val menuHelpAbout: String,
    val statusRendering: String,
    val statusReady: String,
    val statusNoDiagram: String,
) {
    companion object {
        val DE = Strings(
            menuFile = "Datei",
            menuFileNew = "Neu",
            menuFileOpen = "Öffnen…",
            menuFileSave = "Speichern",
            menuFileSaveAs = "Speichern unter…",
            menuFileQuit = "Beenden",
            menuEdit = "Bearbeiten",
            menuEditUndo = "Rückgängig",
            menuEditRedo = "Wiederholen",
            menuEditFind = "Suchen…",
            menuView = "Ansicht",
            menuViewTheme = "Theme",
            menuViewLanguage = "Sprache",
            menuHelp = "Hilfe",
            menuHelpDocs = "Dokumentation…",
            menuHelpAbout = "Über kUML…",
            statusRendering = "Rendering…",
            statusReady = "Bereit",
            statusNoDiagram = "Kein Diagramm",
        )

        val EN = Strings(
            menuFile = "File",
            menuFileNew = "New",
            menuFileOpen = "Open…",
            menuFileSave = "Save",
            menuFileSaveAs = "Save As…",
            menuFileQuit = "Quit",
            menuEdit = "Edit",
            menuEditUndo = "Undo",
            menuEditRedo = "Redo",
            menuEditFind = "Find…",
            menuView = "View",
            menuViewTheme = "Theme",
            menuViewLanguage = "Language",
            menuHelp = "Help",
            menuHelpDocs = "Documentation…",
            menuHelpAbout = "About kUML…",
            statusRendering = "Rendering…",
            statusReady = "Ready",
            statusNoDiagram = "No diagram",
        )

        fun forLanguage(lang: String): Strings = if (lang == "de") DE else EN
    }
}
