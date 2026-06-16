package dev.kuml.desktop.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.kuml.codegen.m2m.TransformerRegistry
import dev.kuml.renderer.theme.core.ThemeRegistry

/**
 * V3.0.13 — Plugin-Manager-Dialog.
 *
 * Listet alle via ServiceLoader geladenen Erweiterungen auf drei Tabs:
 * - Themes (ThemeRegistry)
 * - Transformers (TransformerRegistry)
 * - Reverse-Engines (ReverseEngineRegistry, via Reflection — graceful fallback)
 */
@Composable
fun PluginManagerPane(onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        title = "Plugin Manager",
        state = rememberDialogState(width = 700.dp, height = 500.dp),
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        text = "Geladene Erweiterungen",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    var selected by remember { mutableStateOf(0) }
                    val tabs = listOf("Themes", "Transformers", "Reverse-Engines")
                    TabRow(selectedTabIndex = selected) {
                        tabs.forEachIndexed { i, title ->
                            Tab(
                                selected = i == selected,
                                onClick = { selected = i },
                                text = { Text(title) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when (selected) {
                        0 -> PluginSection(
                            items = loadThemes(),
                            emptyText = "Keine Themes geladen",
                        )
                        1 -> PluginSection(
                            items = loadTransformers(),
                            emptyText = "Keine Transformer geladen",
                        )
                        2 -> PluginSection(
                            items = loadReverseEngines(),
                            emptyText = "Keine Reverse-Engines geladen",
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onClose) { Text("Schließen") }
                    }
                }
            }
        }
    }
}

/** Eintrag für die Plugin-Liste. */
data class PluginEntry(val id: String, val kind: String, val description: String)

@Composable
private fun PluginSection(items: List<PluginEntry>, emptyText: String) {
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.id }) { entry ->
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.id, style = MaterialTheme.typography.titleSmall)
                        Text(entry.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = entry.kind,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Loader-Funktionen (internal für Testbarkeit) ───────────────────────────

internal fun loadThemes(): List<PluginEntry> =
    ThemeRegistry.names().map { name -> PluginEntry(name, "Theme", name) }

/**
 * Lädt alle Transformer aus dem [TransformerRegistry].
 * Verwendet `ids()` + `descriptions()` — kein `all()`-Call nötig.
 */
internal fun loadTransformers(): List<PluginEntry> =
    try {
        val descriptions = TransformerRegistry.descriptions()
        TransformerRegistry.ids().map { id ->
            PluginEntry(id, "Transformer", descriptions[id] ?: id)
        }
    } catch (_: Exception) {
        emptyList()
    }

/**
 * Lädt Reverse-Engines via Reflection — graceful Fallback wenn das Modul
 * nicht auf dem Classpath liegt.
 */
internal fun loadReverseEngines(): List<PluginEntry> =
    try {
        val registryClass = Class.forName("dev.kuml.codegen.reverse.registry.ReverseEngineRegistry")
        val allMethod = registryClass.getDeclaredMethod("all")
        @Suppress("UNCHECKED_CAST")
        val engines = allMethod.invoke(null) as? List<*> ?: emptyList<Any>()
        engines.mapNotNull { e ->
            val idField = e?.javaClass?.getDeclaredField("id")?.also { it.isAccessible = true }
            val id = idField?.get(e) as? String ?: return@mapNotNull null
            PluginEntry(id, "Reverse-Engine", id)
        }
    } catch (_: Exception) {
        emptyList()
    }
