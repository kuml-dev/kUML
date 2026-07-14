package dev.kuml.profile.erm

/**
 * Stable string constants for the ERM mapping profile ([ermMappingProfile]).
 *
 * [dev.kuml.transform.umlerm.UmlToErmTransformer] (module `kuml-transform-uml-to-erm`)
 * imports these constants instead of hardcoding the namespace/stereotype/tag-key
 * strings, closing the "silent namespace drift" risk documented in
 * `UmlToExposedPsmTransformer`'s KDoc — if this profile's namespace or a tag key
 * were ever renamed without updating the transformer, the two would otherwise
 * silently stop matching at runtime instead of failing to compile.
 *
 * V3.4.6
 */
public object ErmProfileNames {
    public const val NAMESPACE: String = "dev.kuml.profiles.erm"

    // ── Stereotype names ──────────────────────────────────────────────────────
    public const val ENTITY: String = "Entity"
    public const val INHERITANCE: String = "Inheritance"
    public const val COLUMN: String = "Column"
    public const val ID: String = "Id"
    public const val TRANSIENT: String = "Transient"
    public const val FK: String = "FK"
    public const val JUNCTION_TABLE: String = "JunctionTable"

    // ── Tag keys ──────────────────────────────────────────────────────────────
    public const val TAG_TABLE_NAME: String = "tableName"
    public const val TAG_SCHEMA: String = "schema"
    public const val TAG_KOTLIN_OBJECT_NAME: String = "kotlinObjectName"
    public const val TAG_STRATEGY: String = "strategy"
    public const val TAG_DISCRIMINATOR_COLUMN: String = "discriminatorColumn"
    public const val TAG_COLUMN_NAME: String = "columnName"
    public const val TAG_SQL_TYPE: String = "sqlType"
    public const val TAG_ENUM_TYPE: String = "enumType"
    public const val TAG_FK_ENTITY: String = "fkEntity"
    public const val TAG_FK_ATTRIBUTE: String = "fkAttribute"
    public const val TAG_NULLABLE: String = "nullable"
    public const val TAG_UNIQUE: String = "unique"
    public const val TAG_AUTO_INCREMENT: String = "autoIncrement"
    public const val TAG_CONSTRAINT_NAME: String = "constraintName"
    public const val TAG_ON_DELETE: String = "onDelete"
    public const val TAG_ON_UPDATE: String = "onUpdate"
    public const val TAG_SOURCE_COLUMN: String = "sourceColumn"
    public const val TAG_TARGET_COLUMN: String = "targetColumn"
}
