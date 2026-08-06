package dev.kuml.erm.model

/**
 * Default FK constraint name for an association-derived foreign key.
 *
 * Shared single source of truth for every code generator that needs to name a physical
 * constraint or an Exposed `reference()`/`optReference()` call *explicitly* — currently
 * `kuml-gen-sql`'s `ErmSqlEmitter` (which writes the real `ALTER TABLE ... ADD CONSTRAINT`
 * DDL that Flyway applies to the actual database) and `kuml-codegen-m2m-exposed`'s
 * `ErmExposedEmitter` (which must describe that same physical constraint to Exposed via an
 * explicit `fkName` argument, rather than letting Exposed compute its own, different,
 * default at runtime).
 *
 * Extracted here — rather than duplicating the string template in both emitters — specifically
 * because the two emitters *did* duplicate it independently once already, silently diverged
 * (`fk_<table>_<column>` vs. Exposed's own runtime default of `fk_<table>_<column>__<targetcolumn>`),
 * and that divergence went undetected by every kUML test because nothing compared the two
 * emitters' output against each other — it only surfaced when a downstream consumer's CI
 * (real Postgres via Testcontainers) applied the generated SQL and asked Exposed's own
 * `SchemaUtils.statementsRequiredToActualizeScheme` whether the compiled `Table` objects agreed
 * with the migrated schema. Having one function, used by both call sites, makes that class of
 * regression structurally impossible to reintroduce by editing only one of the two emitters.
 */
public fun ermDefaultForeignKeyConstraintName(
    tableName: String,
    columnName: String,
): String = "fk_${tableName}_$columnName"
