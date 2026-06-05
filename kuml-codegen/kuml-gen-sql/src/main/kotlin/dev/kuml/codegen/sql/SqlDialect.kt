package dev.kuml.codegen.sql

public enum class SqlDialect(
    public val key: String,
) {
    POSTGRES("postgres"),
    MYSQL("mysql"),
    H2("h2"),
    SQLITE("sqlite"),
    ;

    public companion object {
        public fun from(raw: String): SqlDialect =
            entries.firstOrNull { it.key == raw.lowercase() }
                ?: error("Unknown sql-dialect: '$raw'. Allowed: ${entries.joinToString(", ") { it.key }}.")
    }
}
