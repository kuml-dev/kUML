package dev.kuml.mcp

/**
 * Entry point for the kUML MCP server.
 *
 * Usage (add to MCP client config):
 * ```json
 * {
 *   "mcpServers": {
 *     "kuml": {
 *       "command": "/path/to/kuml-mcp/bin/kuml-mcp"
 *     }
 *   }
 * }
 * ```
 */
public fun main() {
    // logback.xml in this module's resources does the actual appender/pattern
    // configuration: everything goes to stderr, never stdout — stdout carries
    // the JSON-RPC protocol. configureLogging() only normalizes KUML_LOG_LEVEL
    // before that file's ${KUML_LOG_LEVEL:-WARN} substitution runs.
    configureLogging()
    McpServer.run()
}

/**
 * Normalizes `KUML_LOG_LEVEL` before the first SLF4J logger is touched. Visible
 * for testing.
 */
internal fun configureLogging() {
    normalizeInvalidLogLevel(MCP_DEFAULT_LOG_LEVEL)
}

/**
 * Default root log level for the MCP server, matching `logback.xml`'s
 * `${KUML_LOG_LEVEL:-WARN}` substitution. Kept as a named constant so
 * [normalizeInvalidLogLevel]'s fallback can never silently drift out of sync
 * with the XML default.
 */
internal const val MCP_DEFAULT_LOG_LEVEL = "WARN"

/** Level names Logback's `Level.toLevel(String)` actually recognizes. */
private val VALID_KUML_LOG_LEVELS = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "ALL")

/**
 * Pure decision of what (if anything) [normalizeInvalidLogLevel] should
 * override `KUML_LOG_LEVEL` to. Separated from the actual env/system-property
 * plumbing so the DEBUG-fallback bug this guards against can be unit-tested
 * without needing to mutate real OS environment variables (which the JVM does
 * not allow post-launch).
 *
 * Returns `null` when Logback's own `${KUML_LOG_LEVEL:-default}` substitution
 * should be left alone — either [raw] is unset, or it's already a level name
 * `Level.toLevel(String)` recognizes. Returns [default] when [raw] is set but
 * invalid (a plausible-but-wrong guess like `SILENT`, `NONE`, or `quiet`) —
 * Logback's parser would otherwise silently fall back to DEBUG for such a
 * value: the exact opposite of "quieter" that whoever set it presumably
 * wanted. Duplicated in kuml-cli's and kuml-desktop's Main.kt — same rationale
 * as the duplicated third-party logger pins across the three logback config
 * XMLs, see the comment there.
 */
internal fun normalizedLogLevelOverride(
    raw: String?,
    default: String,
): String? = if (raw != null && raw.trim().uppercase() !in VALID_KUML_LOG_LEVELS) default else null

/**
 * Guards against `Level.toLevel(String)`'s silent fallback to DEBUG for any
 * unrecognized `KUML_LOG_LEVEL` value — see [normalizedLogLevelOverride] for
 * the decision itself.
 *
 * Reads the raw value the same way Logback's own `${KUML_LOG_LEVEL:-default}`
 * substitution would — system property first, OS environment variable as
 * fallback (context property, then system property, then OS environment; see
 * `OptionHelper.propertyLookup`) — so a value set only via `-DKUML_LOG_LEVEL=`
 * is guarded exactly like one set via the OS environment. Setting a JVM system
 * property of the same name then pre-empts the OS environment variable for
 * Logback's own later lookup, so this must run before the first SLF4J logger
 * is touched. Visible for testing.
 */
internal fun normalizeInvalidLogLevel(default: String) {
    val raw = System.getProperty("KUML_LOG_LEVEL") ?: System.getenv("KUML_LOG_LEVEL")
    normalizedLogLevelOverride(raw = raw, default = default)?.let {
        System.setProperty("KUML_LOG_LEVEL", it)
    }
}
