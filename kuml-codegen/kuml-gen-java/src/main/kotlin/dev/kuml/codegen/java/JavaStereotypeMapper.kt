package dev.kuml.codegen.java

import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.TagValue

/**
 * Mappt bekannte UML-Stereotypen auf Java-Annotation-Strings.
 *
 * V1.1.4 unterstützt vier Mappings:
 *  - `«Entity»{tableName=…, schema=…}` → `@jakarta.persistence.Entity(name="…", schema="…")`
 *  - `«Id»`                            → `@jakarta.persistence.Id`
 *  - `«Column»{name=…, nullable=…}`    → `@jakarta.persistence.Column(name="…", nullable=…)`
 *  - `«Transient»`                     → `@jakarta.persistence.Transient`
 *
 * Stereotypen werden per **String** gematcht — der Generator hat keine
 * Dependency auf die Profile-Module (D14).
 */
internal object JavaStereotypeMapper {
    fun toAnnotation(app: AppliedStereotype): String? =
        when (app.stereotypeName) {
            "Entity" -> {
                val name = app.tags["tableName"]?.stringOrNull()
                val schema = app.tags["schema"]?.stringOrNull()
                val args =
                    listOfNotNull(
                        name?.let { "name = \"$it\"" },
                        schema?.let { "schema = \"$it\"" },
                    ).joinToString(", ")
                if (args.isEmpty()) "@jakarta.persistence.Entity" else "@jakarta.persistence.Entity($args)"
            }
            "Id" -> "@jakarta.persistence.Id"
            "Column" -> {
                val name = app.tags["name"]?.stringOrNull()
                val nullable = app.tags["nullable"]?.booleanOrNull()
                val args =
                    listOfNotNull(
                        name?.let { "name = \"$it\"" },
                        nullable?.let { "nullable = $it" },
                    ).joinToString(", ")
                if (args.isEmpty()) "@jakarta.persistence.Column" else "@jakarta.persistence.Column($args)"
            }
            "Transient" -> "@jakarta.persistence.Transient"
            else -> null
        }

    /** True wenn das Element wegen `«Transient»`/`«Internal»` nicht in den Java-Output soll. */
    fun isExcluded(stereotypes: List<AppliedStereotype>): Boolean =
        stereotypes.any { it.stereotypeName == "Transient" || it.stereotypeName == "Internal" }

    private fun TagValue.stringOrNull(): String? = (this as? TagValue.StringVal)?.v

    private fun TagValue.booleanOrNull(): Boolean? = (this as? TagValue.BoolVal)?.v
}
