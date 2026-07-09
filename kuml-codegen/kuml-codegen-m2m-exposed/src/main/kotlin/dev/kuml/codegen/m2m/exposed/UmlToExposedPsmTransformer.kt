package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.toTagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlProperty

/**
 * Transforms a UML class diagram (PIM) into an Exposed-annotated UML class diagram
 * (PSM, ADR-0016 Variante A).
 *
 * Unlike [UmlToExposedTransformer] (Variante B, which emits Kotlin Exposed `Table`
 * object source text directly), this transformer produces a **renderable** PSM:
 * a [KumlDiagram] with the exact same structural shape as the input, but with
 * [dev.kuml.profile.exposed.exposedProfile] stereotypes applied to classes,
 * attributes, and associations. The result can be:
 * - rendered as a UML class diagram (embeddable in Asciidoctor docs via `[kuml]`),
 * - fed into [dev.kuml.codegen.sql.SqlDdlGenerator] to produce a Flyway baseline
 *   (see the dual-apply note below for why this works unmodified),
 * - chained into [UmlToExposedTransformer] via [dev.kuml.codegen.m2m.TransformChain]
 *   for a full PIM → PSM → Kotlin source pipeline.
 *
 * ### Class-level annotation: dual-apply `Table` + `Entity`
 *
 * `kuml-gen-sql`'s `SqlNames.tableName()` recognizes only the *literal* stereotype
 * name `"Entity"` (with tag `tableName`), regardless of which profile's namespace
 * applied it. To keep the PSM directly consumable by `SqlDdlGenerator` without any
 * changes to that module, every class receives **two** applied stereotypes:
 * - `«Table»` (from [dev.kuml.profile.exposed.exposedProfile]) — the semantically
 *   correct, renderable Exposed stereotype.
 * - `«Entity»` (literal name, JavaEE namespace) — cosmetically redundant, but
 *   required so `SqlDdlGenerator`/`SqlNames` pick up the same `tableName` value.
 *
 * This is intentional duplication, not an oversight. `«Table»` and `«Entity»` are
 * NOT related via stereotype specialization (`specializes`) — they are independent,
 * side-by-side applications.
 *
 * ### Association-level annotation: cosmetic `«FK»`
 *
 * `kuml-gen-sql` derives foreign keys purely from [UmlAssociation] multiplicities,
 * never from stereotypes. The `«FK»` stereotype applied here is therefore
 * presentational only (for the rendered diagram) — it carries no semantic weight
 * for code generation. Only [UmlAssociation] implements `Stereotypable` in this
 * metamodel ([dev.kuml.uml.UmlAssociationEnd] does not), so `«FK»` targets
 * `UmlMetaclass.Association` as a whole, not an individual end.
 *
 * ### Known limitations
 * - Single-pass assumption: this transformer assumes the input PIM has no
 *   pre-existing `Table`/`Entity`/`Column`/`FK` applied stereotypes. Running it
 *   twice on its own output would (mostly) duplicate stereotype applications,
 *   except for the `Id` stereotype which has an explicit idempotency guard.
 * - `profileNamespace` string constants are hardcoded here rather than looked up
 *   via `ProfileRegistry`, matching the profile's own namespace literals. If a
 *   profile's namespace is renamed without updating this transformer, the two
 *   values would silently drift — acceptable risk for this wave.
 * - Name/type helper logic (`toSnakeCase`, `toPlural`, column-type mapping) is
 *   intentionally duplicated from [UmlToExposedTransformer] rather than shared,
 *   to keep this addition fully independent of the existing, working Variante B
 *   transformer (which must not be modified).
 *
 * ### Identifier safety
 *
 * [dev.kuml.uml.UmlClass.name] and [dev.kuml.uml.UmlProperty.name] are plain,
 * unconstrained strings — a model author (or a programmatic model producer) can set
 * a class/attribute name containing SQL metacharacters (`"`, `;`, `--`, whitespace,
 * SQL keywords, …). Since the derived `tableName`/`name` tag values produced here are
 * later consumed verbatim by `kuml-gen-sql`'s `SqlDdlGenerator` (string-interpolated
 * into `CREATE TABLE`/`ALTER TABLE` DDL) and rendered into `«Table»`/`«Column»` diagram
 * labels, every derived identifier is validated with [requireSafeSqlIdentifier] before
 * being placed into a tag. An unsafe source name fails the transform with
 * [UnsafeUmlNameException] instead of silently emitting malformed/injectable DDL —
 * mirroring the fail-fast, no-mangling convention already used by
 * [UmlToExposedTransformer]'s `requireValidKotlinIdentifier`.
 *
 * ### Staggered replacement by the ERM path (kUML V3.4.6+, not yet in effect)
 *
 * `kuml-transform-uml-to-erm` (V3.4.6) introduces a typed [dev.kuml.erm.model.ErmModel]
 * successor to this dual-annotation ("`«Table»` + `«Entity»`") workaround. The
 * replacement was staggered across three waves: V3.4.7 repointed `kuml-gen-sql`
 * at [dev.kuml.erm.model.ErmModel] input (removing the need for the dual-apply
 * hack this class relies on for that consumer), and **V3.4.8 repoints the Exposed
 * source-code transformer at [dev.kuml.erm.model.ErmModel] too** (`erm-to-exposed` /
 * `uml-to-exposed-via-erm`, see [dev.kuml.codegen.m2m.exposed.ErmToExposedTransformer] /
 * [dev.kuml.codegen.m2m.exposed.UmlToExposedViaErmScriptTransformer]) — which is what
 * makes *this* class deprecatable. It remains fully active and unmodified (its own
 * tests are unchanged and green) for backward compatibility. See ADR-0016 and
 * [[kUML V3.4]] (Vault) for the plan.
 */
@Deprecated(
    "Superseded by the uml-to-erm + erm-to-exposed chain (uml-to-exposed-via-erm, V3.4.8). Kept for backward compatibility.",
)
public class UmlToExposedPsmTransformer : KumlTransformer<KumlDiagram, KumlDiagram> {
    override val id: String = "uml-to-exposed-psm"
    override val description: String =
        "UML class diagram (PIM) → Exposed-annotated UML class diagram (PSM, ADR-0016 Variante A) " +
            "— renderable and directly consumable by kuml-gen-sql's SqlDdlGenerator."

    override fun transform(
        source: KumlDiagram,
        ctx: TransformContext,
    ): TransformResult<KumlDiagram> {
        var trace = TransformTrace()
        val classById: Map<String, UmlClass> =
            source.elements.filterIsInstance<UmlClass>().associateBy { it.id }

        val newElements: List<KumlElement> =
            source.elements.map { element ->
                when (element) {
                    is UmlClass -> {
                        val (annotated, links) = annotateClass(element)
                        trace = links.fold(trace) { acc, l -> acc.plus(l) }
                        annotated
                    }
                    is UmlAssociation -> {
                        val (annotated, link) = annotateAssociation(element, classById)
                        if (link != null) trace = trace.plus(link)
                        annotated
                    }
                    else -> element
                }
            }

        return TransformResult.Success(source.copy(elements = newElements), trace)
    }

    // ── Class annotation: dual-apply Table + Entity ─────────────────────────

    private fun annotateClass(cls: UmlClass): Pair<UmlClass, List<TraceabilityLink>> {
        val tableName = requireSafeSqlIdentifier(toSnakeCase(cls.name).toPlural(), "table name", cls.id)

        val tableStereotype =
            KumlStereotypeApplication(
                profileNamespace = EXPOSED_NAMESPACE,
                stereotypeName = "Table",
                tags = mapOf("tableName" to tableName, "schema" to "public").mapValues { it.value.toTagValue() },
            )
        // Dual-apply: SqlNames.tableName() only recognizes the LITERAL stereotype name
        // "Entity" (with tag "tableName"), regardless of profile namespace. Apply a
        // second, JavaEE-namespaced "Entity" stereotype with the SAME tableName value
        // so this PSM is directly consumable by SqlDdlGenerator unmodified.
        val entityStereotype =
            KumlStereotypeApplication(
                profileNamespace = JAVAEE_NAMESPACE,
                stereotypeName = "Entity",
                tags = mapOf("tableName" to tableName).mapValues { it.value.toTagValue() },
            )

        val newAttributes = cls.attributes.map { annotateAttribute(it) }

        val newClass =
            cls.copy(
                attributes = newAttributes,
                appliedStereotypes = cls.appliedStereotypes + tableStereotype + entityStereotype,
            )

        val links = listOf(TraceabilityLink(cls.id, tableName, RULE_CLASS_TO_TABLE))
        return newClass to links
    }

    // ── Attribute annotation: Id | Column | Transient(unchanged) ────────────

    private fun annotateAttribute(attr: UmlProperty): UmlProperty {
        val stereotypeNames =
            attr.stereotypes.map { it.lowercase() } +
                attr.appliedStereotypes.map { it.stereotypeName.lowercase() }

        return when {
            attr.name.lowercase() == "id" || "id" in stereotypeNames -> {
                if (attr.appliedStereotypes.any { it.stereotypeName == "Id" }) {
                    attr // already has Id — no duplicate re-apply
                } else {
                    attr.copy(
                        appliedStereotypes =
                            attr.appliedStereotypes +
                                KumlStereotypeApplication(
                                    profileNamespace = EXPOSED_NAMESPACE,
                                    stereotypeName = "Id",
                                ),
                    )
                }
            }
            "transient" in stereotypeNames -> {
                // Left unchanged — no Column stereotype applied.
                attr
            }
            else -> {
                val colName = requireSafeSqlIdentifier(toSnakeCase(attr.name), "column name", attr.id)
                val columnType = exposedColumnTypeName(attr.type.name)
                attr.copy(
                    appliedStereotypes =
                        attr.appliedStereotypes +
                            KumlStereotypeApplication(
                                profileNamespace = EXPOSED_NAMESPACE,
                                stereotypeName = "Column",
                                tags =
                                    mapOf(
                                        "columnType" to columnType,
                                        "name" to colName,
                                    ).mapValues { it.value.toTagValue() },
                            ),
                )
            }
        }
    }

    // ── Association annotation: cosmetic FK stereotype ──────────────────────

    private fun annotateAssociation(
        assoc: UmlAssociation,
        classById: Map<String, UmlClass>,
    ): Pair<UmlAssociation, TraceabilityLink?> {
        if (assoc.ends.size != 2) return assoc to null
        val sourceEnd = assoc.ends[0]
        val targetEnd = assoc.ends[1]
        val sourceClass = classById[sourceEnd.typeId]
        val targetClass = classById[targetEnd.typeId]
        if (sourceClass == null || targetClass == null) return assoc to null

        val upper = targetEnd.multiplicity.upper
        // Mirror UmlToExposedTransformer's FK-side detection: many-to-one / one-to-one
        // only (upper == 1 on the target end); *-to-many is not FK-bearing here.
        if (upper == null || upper > 1) return assoc to null
        if (sourceClass.id == targetClass.id) return assoc to null // self-referential — cosmetic only, skip

        val targetTableName =
            requireSafeSqlIdentifier(toSnakeCase(targetClass.name).toPlural(), "FK target table name", assoc.id)

        val fkStereotype =
            KumlStereotypeApplication(
                profileNamespace = EXPOSED_NAMESPACE,
                stereotypeName = "FK",
                tags = mapOf("targetTable" to targetTableName).mapValues { it.value.toTagValue() },
            )
        val annotated = assoc.copy(appliedStereotypes = assoc.appliedStereotypes + fkStereotype)
        return annotated to TraceabilityLink(assoc.id, targetTableName, RULE_ASSOC_TO_FK)
    }

    // ── Shared name/type helpers (duplicated intentionally — see class KDoc) ──

    private fun toSnakeCase(name: String): String {
        val sb = StringBuilder()
        for ((i, ch) in name.withIndex()) {
            if (ch.isUpperCase() && i > 0) sb.append('_')
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun String.toPlural(): String =
        when {
            endsWith("y") && length > 1 && this[length - 2].lowercaseChar() !in "aeiou" ->
                dropLast(1) + "ies"
            endsWith("s") ||
                endsWith("x") ||
                endsWith("z") ||
                endsWith("ch") ||
                endsWith("sh") -> "${this}es"
            else -> "${this}s"
        }

    private fun exposedColumnTypeName(umlType: String): String =
        when (umlType.lowercase()) {
            "string", "str" -> "varchar"
            "integer", "int" -> "integer"
            "long" -> "long"
            "boolean", "bool" -> "bool"
            "double", "float" -> "double"
            else -> "varchar"
        }

    /**
     * Validates that [name] is safe to embed as a SQL identifier — [dev.kuml.uml.UmlClass.name]/
     * [dev.kuml.uml.UmlProperty.name] are unconstrained strings, and the value produced here is
     * later interpolated verbatim into DDL by `SqlDdlGenerator`/`SqlNames` (and shown in
     * `«Table»`/`«Column»` diagram labels). Only `[A-Za-z_][A-Za-z0-9_]*` (capped at 63 chars,
     * PostgreSQL's unquoted identifier limit) is accepted; anything else fails the transform
     * with [UnsafeUmlNameException] rather than being silently mangled or passed through.
     *
     * @throws UnsafeUmlNameException if [name] is not a safe SQL identifier.
     */
    private fun requireSafeSqlIdentifier(
        name: String,
        what: String,
        elementId: String,
    ): String {
        if (!SAFE_SQL_IDENTIFIER_REGEX.matches(name)) {
            throw UnsafeUmlNameException(
                "uml-to-exposed-psm: derived $what '$name' (element $elementId) is not a safe SQL " +
                    "identifier — only [A-Za-z_][A-Za-z0-9_]{0,62} is accepted, refusing to annotate PSM.",
            )
        }
        return name
    }

    private companion object {
        const val EXPOSED_NAMESPACE = "dev.kuml.profiles.exposed"
        const val JAVAEE_NAMESPACE = "dev.kuml.profiles.javaee"
        const val RULE_CLASS_TO_TABLE = "uml-class-to-exposed-psm-table"
        const val RULE_ASSOC_TO_FK = "uml-association-to-exposed-psm-fk"

        val SAFE_SQL_IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")
    }
}

/**
 * Thrown by [UmlToExposedPsmTransformer] when a class/attribute/association name yields a
 * derived table/column identifier that is not safe to embed in generated SQL DDL or diagram
 * labels. See [UmlToExposedPsmTransformer]'s "Identifier safety" KDoc section.
 */
public class UnsafeUmlNameException(
    message: String,
) : RuntimeException(message)

/** ServiceLoader provider for [UmlToExposedPsmTransformer]. */
@Suppress("DEPRECATION")
public class UmlToExposedPsmTransformerProvider : KumlTransformerProvider {
    override fun transformer(): UmlToExposedPsmTransformer = UmlToExposedPsmTransformer()
}
