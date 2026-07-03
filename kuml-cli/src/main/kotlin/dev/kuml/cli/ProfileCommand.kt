package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.kuml.profile.KumlProfile
import dev.kuml.profile.ProfileRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─────────────────────────────────────────────────────────────────────────────
// Exit codes for `kuml profile` subcommands
//
//  0 — OK
//  1 — profile namespace not found
//  2 — wrong usage (handled by Clikt)
//  4 — self-check violation (D21, consistent with validate exit codes)
// ─────────────────────────────────────────────────────────────────────────────

private const val PROFILE_NOT_FOUND = 1
private const val PROFILE_SELF_CHECK_VIOLATION = 4

// Single shared pretty-printing Json instance — creating a new Json {} per call
// is flagged by the kotlinx.serialization compiler plugin as needlessly slow.
private val kumlPrettyJson = Json { prettyPrint = true }

/**
 * Top-level `kuml profile` subcommand.
 *
 * Provides three sub-subcommands:
 * - `list`         — list all registered profiles
 * - `show <ns>`    — show details of a specific profile
 * - `validate <ns>`— self-check a profile's internal consistency
 */
internal class ProfileCommand : CliktCommand(name = "profile") {
    init {
        subcommands(
            ProfileListCommand(),
            ProfileShowCommand(),
            ProfileValidateCommand(),
        )
    }

    override fun help(context: Context): String = "Inspect and validate UML profiles registered in the kUML profile registry."

    override fun run() = Unit
}

// ── `kuml profile list` ───────────────────────────────────────────────────────

internal class ProfileListCommand : CliktCommand(name = "list") {
    private val outputFormat by option("-o", "--output", help = "Output format (text or json)")
        .choice("text", "json")
        .default("text")

    override fun help(context: Context): String = "List all registered kUML profiles."

    override fun run() {
        ProfileRegistry.loadFromClasspath()
        val profiles = ProfileRegistry.all().sortedBy { it.namespace }

        when (outputFormat) {
            "json" -> {
                val items = profiles.map { it.toListItem() }
                echo(kumlPrettyJson.encodeToString(ProfileListJson(items)))
            }
            else -> {
                if (profiles.isEmpty()) {
                    echo("No profiles registered.")
                    return
                }
                echo("Registered profiles (${profiles.size}):\n")
                for (p in profiles) {
                    echo("  ${p.namespace}")
                    echo("    Name:        ${p.name}")
                    echo("    Version:     ${p.version}")
                    echo("    Stereotypes: ${p.stereotypes.size}")
                    if (p.extendsProfiles.isNotEmpty()) {
                        echo("    Extends:     ${p.extendsProfiles.joinToString(", ")}")
                    }
                    echo("")
                }
            }
        }
    }
}

// ── `kuml profile show <namespace>` ──────────────────────────────────────────

internal class ProfileShowCommand : CliktCommand(name = "show") {
    private val namespace by argument(help = "Profile namespace (e.g. dev.kuml.profiles.javaee)")

    private val outputFormat by option("-o", "--output", help = "Output format (text or json)")
        .choice("text", "json")
        .default("text")

    override fun help(context: Context): String = "Show details of a specific kUML profile."

    override fun run() {
        ProfileRegistry.loadFromClasspath()
        val profile =
            ProfileRegistry.get(namespace)
                ?: run {
                    echo("Profile not found: '$namespace'", err = true)
                    echo(
                        "Available: ${ProfileRegistry.all().map { it.namespace }.sorted().joinToString(", ")}",
                        err = true,
                    )
                    throw ProgramResult(PROFILE_NOT_FOUND)
                }

        when (outputFormat) {
            "json" -> {
                val detail = profile.toDetailJson()
                echo(kumlPrettyJson.encodeToString(detail))
            }
            else -> printProfileText(profile)
        }
    }

    private fun printProfileText(profile: KumlProfile) {
        echo("Profile: ${profile.name}")
        echo("  Namespace:   ${profile.namespace}")
        echo("  Version:     ${profile.version}")
        echo("  Description: ${profile.description}")
        if (profile.extendsProfiles.isNotEmpty()) {
            echo("  Extends:     ${profile.extendsProfiles.joinToString(", ")}")
        }
        echo("  Stereotypes: ${profile.stereotypes.size}\n")

        for (s in profile.stereotypes) {
            echo("  «${s.name}» → ${s.targetMetaclass}")
            if (s.specializes != null) {
                echo("    Specializes: ${s.specializes}")
            }
            if (s.properties.isNotEmpty()) {
                echo("    Properties:")
                for (p in s.properties) {
                    val required = if (p.required) " [required]" else ""
                    val default = if (p.default != null) " = ${p.default}" else ""
                    echo("      ${p.name}: ${p.type.simpleName}$required$default")
                }
            }
            if (s.constraints.isNotEmpty()) {
                echo("    Constraints:")
                for (c in s.constraints) {
                    echo("      ${c.name}: ${c.body}")
                }
            }
            echo("")
        }
    }
}

// ── `kuml profile validate <namespace>` ──────────────────────────────────────

/**
 * Self-check for a profile's internal consistency.
 *
 * Checks performed:
 * 1. All stereotypes have [dev.kuml.profile.KumlStereotype.targetMetaclass] set (always true — builder enforces).
 * 2. All `specializes` references resolve within the transitive `extends`-closure.
 * 3. All OCL constraint bodies parse without syntax errors.
 *
 * This is a profile self-check, not a model validation.
 * Use `kuml validate --check-stereotypes` to validate stereotype applications in a model.
 */
internal class ProfileValidateCommand : CliktCommand(name = "validate") {
    private val namespace by argument(help = "Profile namespace to self-check")

    override fun help(context: Context): String = "Self-check a kUML profile for internal consistency (specializes closure, OCL syntax)."

    override fun run() {
        ProfileRegistry.loadFromClasspath()
        val profile =
            ProfileRegistry.get(namespace)
                ?: run {
                    echo("Profile not found: '$namespace'", err = true)
                    throw ProgramResult(PROFILE_NOT_FOUND)
                }

        val violations = mutableListOf<String>()

        // Check 1: specializes references resolve within the extends-closure
        val closure = buildTransitiveClosure(namespace)
        val allStereotypeNames =
            closure
                .flatMap { ns ->
                    ProfileRegistry.get(ns)?.stereotypes?.map { it.name } ?: emptyList()
                }.toSet()

        for (s in profile.stereotypes) {
            val parent = s.specializes ?: continue
            if (parent !in allStereotypeNames) {
                violations +=
                    "Stereotype '${s.name}' specializes '$parent', " +
                    "but '$parent' is not found in the extends-closure: $closure"
            }
        }

        // Check 2: OCL constraint syntax (parse only)
        for (s in profile.stereotypes) {
            for (c in s.constraints) {
                try {
                    parseOclSyntax(c.body)
                } catch (e: Exception) {
                    violations +=
                        "Stereotype '${s.name}' constraint '${c.name}': OCL syntax error — ${e.message}"
                }
            }
        }

        if (violations.isEmpty()) {
            echo("Profile '${profile.namespace}' self-check passed.")
        } else {
            echo("Profile '${profile.namespace}' self-check FAILED:\n")
            for (v in violations) {
                echo("  - $v")
            }
            throw ProgramResult(PROFILE_SELF_CHECK_VIOLATION)
        }
    }

    private fun buildTransitiveClosure(startNamespace: String): Set<String> {
        val visited = mutableSetOf<String>()

        fun visit(ns: String) {
            if (ns in visited) return
            visited += ns
            ProfileRegistry.get(ns)?.extendsProfiles?.forEach { visit(it) }
        }
        visit(startNamespace)
        return visited
    }

    /** Parse OCL expression for syntax errors using the public OclValidator API. */
    private fun parseOclSyntax(expression: String) {
        dev.kuml.core.ocl.OclValidator
            .parseOclSyntax(expression)
    }
}

// ── JSON serialization types ──────────────────────────────────────────────────

@Serializable
internal data class ProfileListJson(
    @SerialName("profiles") val profiles: List<ProfileListItem>,
)

@Serializable
internal data class ProfileListItem(
    val namespace: String,
    val name: String,
    val version: String,
    @SerialName("stereotype_count") val stereotypeCount: Int,
    @SerialName("extends") val extendsProfiles: List<String>,
)

@Serializable
internal data class ProfileDetailJson(
    val namespace: String,
    val name: String,
    val version: String,
    val description: String,
    @SerialName("extends") val extendsProfiles: List<String>,
    val stereotypes: List<StereotypeDetailJson>,
)

@Serializable
internal data class StereotypeDetailJson(
    val name: String,
    @SerialName("target_metaclass") val targetMetaclass: String,
    val specializes: String?,
    val properties: List<PropertyDetailJson>,
    val constraints: List<ConstraintDetailJson>,
)

@Serializable
internal data class PropertyDetailJson(
    val name: String,
    val type: String,
    val required: Boolean,
    val default: String?,
)

@Serializable
internal data class ConstraintDetailJson(
    val name: String,
    val body: String,
)

// ── Extension functions ────────────────────────────────────────────────────────

private fun KumlProfile.toListItem(): ProfileListItem =
    ProfileListItem(
        namespace = namespace,
        name = name,
        version = version,
        stereotypeCount = stereotypes.size,
        extendsProfiles = extendsProfiles,
    )

private fun KumlProfile.toDetailJson(): ProfileDetailJson =
    ProfileDetailJson(
        namespace = namespace,
        name = name,
        version = version,
        description = description,
        extendsProfiles = extendsProfiles,
        stereotypes =
            stereotypes.map { s ->
                StereotypeDetailJson(
                    name = s.name,
                    targetMetaclass = s.targetMetaclass.name,
                    specializes = s.specializes,
                    properties =
                        s.properties.map { p ->
                            PropertyDetailJson(
                                name = p.name,
                                type = p.type.simpleName ?: "?",
                                required = p.required,
                                default = p.default?.toString(),
                            )
                        },
                    constraints =
                        s.constraints.map { c ->
                            ConstraintDetailJson(name = c.name, body = c.body)
                        },
                )
            },
    )
