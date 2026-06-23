package dev.kuml.desktop.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.kuml.codegen.m2m.TransformerRegistry
import dev.kuml.plugin.loader.registry.PluginRegistryClient
import dev.kuml.plugin.loader.registry.PluginRegistryEntry
import dev.kuml.plugin.loader.registry.PluginStatsFormat
import dev.kuml.plugin.loader.registry.UpdateCheckResult
import dev.kuml.plugin.loader.registry.UpdateCheckService
import dev.kuml.renderer.theme.core.ThemeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * V3.0.13 — Plugin-Manager-Dialog.
 *
 * Listet alle via ServiceLoader geladenen Erweiterungen auf vier Tabs:
 * - Themes (ThemeRegistry)
 * - Transformers (TransformerRegistry)
 * - Reverse-Engines (ReverseEngineRegistry, via Reflection — graceful fallback)
 * - Registry (V3.1.12) — Browse available plugins from plugins.kuml.dev
 */
@Composable
fun PluginManagerPane(onClose: () -> Unit) {
    // Lazy update-badge fetch: fires once when the pane opens, off the UI thread.
    // On registry failure the badge stays null — silent graceful degradation.
    var updateBadge by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        updateBadge =
            withContext(Dispatchers.IO) {
                runCatching { computeUpdateBadge(UpdateCheckService().check()) }.getOrNull()
            }
    }

    // ── V3.1.12: Registry tab state ──────────────────────────────────────────
    // null = loading, emptyList = error/no entries
    var registryEntries by remember { mutableStateOf<List<PluginRegistryEntry>?>(null) }
    var registryError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { PluginRegistryClient().fetchIndex().plugins }
                .onSuccess { registryEntries = it }
                .onFailure {
                    registryError = true
                    registryEntries = emptyList()
                }
        }
    }

    DialogWindow(
        onCloseRequest = onClose,
        title = "Plugin Manager",
        state = rememberDialogState(width = 700.dp, height = 500.dp),
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Geladene Erweiterungen",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (updateBadge != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.error,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = updateBadge!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    var selected by remember { mutableStateOf(0) }
                    val tabs = registryTabLabels()
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
                        3 -> RegistryBrowseSection(
                            entries = registryEntries,
                            hasError = registryError,
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

/** Returns the ordered tab label list for the Plugin Manager. Extracted for testability. */
internal fun registryTabLabels(): List<String> = listOf("Themes", "Transformers", "Reverse-Engines", "Registry")

// ── Registry Browse Tab (V3.1.12) ─────────────────────────────────────────────

@Composable
private fun RegistryBrowseSection(
    entries: List<PluginRegistryEntry>?,
    hasError: Boolean,
) {
    when {
        entries == null -> {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        hasError || entries.isEmpty() -> {
            val msg =
                if (hasError) "Registry nicht erreichbar" else "Keine Plugins in der Registry"
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        else -> {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { entry ->
                    RegistryEntryCard(entry)
                }
            }
        }
    }
}

@Composable
private fun RegistryEntryCard(entry: PluginRegistryEntry) {
    var expanded by remember { mutableStateOf(false) }
    val allReviews = entry.reviews.sortedByDescending { it.date }
    val previewReviews = allReviews.take(3)

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            // ── Title row ─────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "v${entry.version}  ·  ${entry.category}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                // Download chip
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "${PluginStatsFormat.compactDownloads(entry.downloadCount)} ↓",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // ── Stats subtitle ────────────────────────────────────────────────
            Text(
                text = registryCardSubtitle(entry),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (entry.rating != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    },
            )
            // ── Reviews ───────────────────────────────────────────────────────
            val displayReviews = if (expanded) allReviews else previewReviews
            if (displayReviews.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                for (review in displayReviews) {
                    val stars =
                        PluginStatsFormat.stars(
                            PluginStatsFormat.clampStars(review.rating).toDouble(),
                        )
                    Text(
                        text = "$stars ${review.author} (${review.date}): ${PluginStatsFormat.truncate(review.comment)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (allReviews.size > 3) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) {
                                "Weniger anzeigen"
                            } else {
                                "Alle Reviews anzeigen (${allReviews.size})"
                            },
                        )
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

// ── Registry card helpers (V3.1.12, internal for testability) ─────────────────

/**
 * Composes the star + rating subtitle text for a registry entry card.
 *
 * Extracted as an `internal` pure function so it can be unit-tested headlessly
 * without a Compose test harness, following the same convention as [computeUpdateBadge].
 *
 * Examples:
 * - rated entry: `"★★★★☆  4.3/5.0 (12 ratings)"`
 * - unrated entry: `"☆☆☆☆☆  no ratings yet"`
 */
internal fun registryCardSubtitle(entry: PluginRegistryEntry): String {
    val stars = PluginStatsFormat.stars(entry.rating)
    val ratingText = PluginStatsFormat.ratingLine(entry.rating, entry.ratingCount)
    return "$stars  $ratingText"
}

// ── Update-Badge (V3.1.11) ────────────────────────────────────────────────────

/**
 * Computes the label text for the update-count badge in the Plugin Manager header.
 *
 * Returns a non-null string only when the registry was reachable AND there is at
 * least one plugin with a newer version — callers show the badge when non-null.
 *
 * This is a pure, side-effect-free function so it can be unit-tested headlessly
 * without a Compose test harness.
 */
internal fun computeUpdateBadge(result: UpdateCheckResult): String? {
    if (!result.registryReachable || result.updateCount == 0) return null
    val count = result.updateCount
    return "$count update${if (count == 1) "" else "s"}"
}
